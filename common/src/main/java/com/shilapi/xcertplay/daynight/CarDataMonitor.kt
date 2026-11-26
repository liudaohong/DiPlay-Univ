package com.shilapi.xcertplay.daynight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Fast vehicle-data poller (speed/RPM/odometer/range) over the same vendor-whitelisted
 * [LampReaderService] that already supplies the headlamp state.
 *
 * This deliberately does not create a second Car connection: the service runs in
 * `com.wt.n2048`, where android.car's property manager is readable, and these properties
 * are floats. The lamp poller stays at 1s; speed/RPM need 100-200ms, so this is a separate
 * 100ms batched AIDL snapshot tick shared across dashboard themes and `$PASCD` location reporting.
 */
object CarDataMonitor {
    private const val TAG = "CarDataMonitor"
    private const val POLL_MS = 100L
    private const val REBIND_MS = 5000L

    object SnapshotKeys {
        const val SPEED_RAW = "speed_raw"
        const val RPM = "rpm"
        const val ODOMETER_KM = "odometer_km"
        const val RANGE_KM = "range_km"
        const val GEAR = "gear"
        const val GEAR_RAW = "gear_raw"
        const val GEAR_TEXT = "gear_text"
        const val TURN_LEFT = "turn_left"
        const val TURN_RIGHT = "turn_right"
        const val HAZARD = "hazard"
        const val TURN_LEFT_BLINK = "turn_left_blink"
        const val TURN_RIGHT_BLINK = "turn_right_blink"
        const val CONNECTED = "connected"
        const val ERROR = "error"
    }

    /** The vendor PERF_VEHICLE_SPEED on this UNI-V already reports km/h. */
    const val SPEED_UNIT_FACTOR = 1f

    data class State(
        val speedRaw: Float = Float.NaN,
        val rpm: Float = Float.NaN,
        val odometerKm: Float = Float.NaN,
        val rangeKm: Float = Float.NaN,
        val gear: Int = 0,
        val gearRaw: Int = 0,
        val gearText: String = "",
        val turnLeft: Boolean = false,
        val turnRight: Boolean = false,
        val hazard: Boolean = false,
        val turnLeftBlink: Boolean = false,
        val turnRightBlink: Boolean = false,
        val connected: Boolean = false,
        val error: String = "",
    )

    @Volatile
    var snapshot: State = State()
        private set

    /**
     * Binder calls here can block for many seconds (field 13:35: an 11.9 s UI STALL inside
     * CarDataMonitor.getGearRaw). This poller must never run on the main thread.
     */
    private val lifecycleLock = Any()
    private val sampleListeners = CopyOnWriteArraySet<(Long, State) -> Unit>()
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var running = false
    private var fullClients = 0
    private var motionClients = 0
    @Volatile private var reader: ILampReader? = null
    @Volatile private var binding = false
    @Volatile private var bindRequested = false
    @Volatile private var lastBindAt = 0L
    private var lastLogAt = 0L

    /** Bench-only overrides used by the PC theme preview; null means use real car data. */
    @Volatile private var benchSpeedKmh: Float? = null
    @Volatile private var benchRpm: Float? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lifecycleLock) {
                if (!running) {
                    runCatching { appContext?.unbindService(this) }
                    return
                }
                reader = service?.let { ILampReader.Stub.asInterface(it) }
                binding = false
            }
            Log.i(TAG, "car data probe connected process=com.wt.n2048")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val stillRunning = synchronized(lifecycleLock) {
                reader = null
                binding = false
                bindRequested = false
                running
            }
            if (stillRunning) Log.i(TAG, "car data probe disconnected")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val active = synchronized(lifecycleLock) { running }
            if (!active) return
            pollOnce()
            synchronized(lifecycleLock) {
                if (running) handler?.postDelayed(this, POLL_MS)
            }
        }
    }

    /** Registers a sample listener invoked on the poller thread with (elapsedMs, state). */
    fun addSampleListener(listener: (Long, State) -> Unit) {
        sampleListeners.add(listener)
    }

    /** Unregisters a previously added sample listener. */
    fun removeSampleListener(listener: (Long, State) -> Unit) {
        sampleListeners.remove(listener)
    }

    /** Call from dashboard theme views that need full vehicle telemetry (speed, RPM, gear, lamps). */
    fun start(context: Context) {
        startClient(context, motionOnly = false)
    }

    /** Call when releasing a full telemetry client started by [start]. */
    fun stop() {
        stopClient(motionOnly = false)
    }

    /** Call from iAP2 tunnel dead reckoning when only speed and gear are needed. */
    fun startMotionOnly(context: Context) {
        startClient(context, motionOnly = true)
    }

    /** Call when releasing a motion-only client started by [startMotionOnly]. */
    fun stopMotionOnly() {
        stopClient(motionOnly = true)
    }

    private fun startClient(context: Context, motionOnly: Boolean) {
        val startedNow = synchronized(lifecycleLock) {
            if (motionOnly) motionClients++ else fullClients++
            if (running) return
            val app = context.applicationContext
            appContext = app
            running = true
            snapshot = State(error = "waiting for probe")
            val thread = HandlerThread("car-data-monitor").apply { start() }
            worker = thread
            val nextHandler = Handler(thread.looper)
            handler = nextHandler
            bindProbeLocked(app, force = true)
            nextHandler.post(tick)
            true
        }
        if (startedNow) {
            Log.i(TAG, "car data monitor start (poll=${POLL_MS}ms motionOnly=$motionOnly)")
        }
    }

    private fun stopClient(motionOnly: Boolean) {
        val stoppedNow = synchronized(lifecycleLock) {
            if (motionOnly) {
                if (motionClients > 0) motionClients--
            } else {
                if (fullClients > 0) fullClients--
            }
            if (fullClients + motionClients > 0 || !running) return
            running = false
            handler?.removeCallbacks(tick)
            handler = null
            worker?.quitSafely()
            worker = null
            val ctx = appContext
            if (ctx != null && bindRequested) {
                runCatching { ctx.unbindService(connection) }
            }
            reader = null
            binding = false
            bindRequested = false
            snapshot = State()
            true
        }
        if (stoppedNow) {
            Log.i(TAG, "car data monitor stop")
        }
    }

    private fun bindProbeLocked(ctx: Context, force: Boolean) {
        if (reader != null || binding) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBindAt < REBIND_MS) return
        lastBindAt = now
        binding = true
        val ok = try {
            ctx.bindService(
                Intent(ctx, LampReaderService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "car data bind failed ${e.javaClass.simpleName}")
            false
        }
        if (ok) {
            bindRequested = true
        } else {
            binding = false
        }
    }

    private fun pollOnce() {
        val (ctx, r, motionOnly) = synchronized(lifecycleLock) {
            if (!running) return
            Triple(appContext, reader, fullClients == 0 && motionClients > 0)
        }
        if (ctx == null) return
        if (r == null) {
            synchronized(lifecycleLock) {
                if (running) bindProbeLocked(ctx, force = false)
            }
            snapshot = State(error = "probe not bound")
            return
        }
        val beforeMs = SystemClock.elapsedRealtime()
        val bundle = try {
            r.getVehicleSnapshot(motionOnly)
        } catch (e: RemoteException) {
            synchronized(lifecycleLock) {
                reader = null
                binding = false
                bindRequested = false
            }
            snapshot = State(error = "remote read failed: ${e.javaClass.simpleName}")
            return
        }
        val afterMs = SystemClock.elapsedRealtime()
        if (bundle == null) {
            snapshot = State(error = "empty vehicle snapshot")
            return
        }
        val sampleElapsedMs = beforeMs + (afterMs - beforeMs) / 2L
        val next = State(
            speedRaw = bundle.getFloat(SnapshotKeys.SPEED_RAW, Float.NaN),
            rpm = bundle.getFloat(SnapshotKeys.RPM, Float.NaN),
            odometerKm = bundle.getFloat(SnapshotKeys.ODOMETER_KM, Float.NaN),
            rangeKm = bundle.getFloat(SnapshotKeys.RANGE_KM, Float.NaN),
            gear = bundle.getInt(SnapshotKeys.GEAR, 0),
            gearRaw = bundle.getInt(SnapshotKeys.GEAR_RAW, 0),
            gearText = bundle.getString(SnapshotKeys.GEAR_TEXT, "") ?: "",
            turnLeft = bundle.getBoolean(SnapshotKeys.TURN_LEFT, false),
            turnRight = bundle.getBoolean(SnapshotKeys.TURN_RIGHT, false),
            hazard = bundle.getBoolean(SnapshotKeys.HAZARD, false),
            turnLeftBlink = bundle.getBoolean(SnapshotKeys.TURN_LEFT_BLINK, false),
            turnRightBlink = bundle.getBoolean(SnapshotKeys.TURN_RIGHT_BLINK, false),
            connected = bundle.getBoolean(SnapshotKeys.CONNECTED, false),
            error = bundle.getString(SnapshotKeys.ERROR, "") ?: "",
        )
        snapshot = next
        for (listener in sampleListeners) {
            runCatching { listener(sampleElapsedMs, next) }
        }

        val now = afterMs
        if (now - lastLogAt >= 5000L &&
            (!next.speedRaw.isNaN() || !next.rpm.isNaN() || !next.odometerKm.isNaN())
        ) {
            lastLogAt = now
            Log.i(
                TAG,
                "car data speedRaw=${next.speedRaw} rpm=${next.rpm} " +
                    "odoKm=${next.odometerKm} rangeKm=${next.rangeKm} " +
                    "gear=${next.gearRaw}/${next.gearText} turnL=${next.turnLeftBlink} " +
                    "turnR=${next.turnRightBlink} hazard=${next.hazard} " +
                    "speedKmh=${speedKmh(next.speedRaw)} connected=${next.connected}",
            )
        }
    }

    /** Raw speed in km/h using the calibratable [SPEED_UNIT_FACTOR]. */
    fun speedKmh(raw: Float = snapshot.speedRaw): Float =
        if (raw.isNaN()) Float.NaN else raw * SPEED_UNIT_FACTOR

    /**
     * Bench override for the PC theme preview. Values are clamped to the dashboard ranges
     * (0..220 km/h, 0..5500 rpm); null clears the override and returns to real data.
     */
    fun setBenchSpeedKmh(value: Float?) {
        benchSpeedKmh = value?.let {
            if (it.isNaN()) null else it.coerceIn(0f, 220f)
        }
        Log.i(TAG, "bench speed override=${benchSpeedKmh ?: "real"}")
    }

    fun setBenchRpm(value: Float?) {
        benchRpm = value?.let {
            if (it.isNaN()) null else it.coerceIn(0f, 5500f)
        }
        Log.i(TAG, "bench rpm override=${benchRpm ?: "real"}")
    }

    fun clearBenchVehicleData() {
        benchSpeedKmh = null
        benchRpm = null
        Log.i(TAG, "bench vehicle override=real")
    }

    /** Theme-facing value: bench override first, then real car data. */
    fun displayedSpeedKmh(): Float = benchSpeedKmh ?: speedKmh(snapshot.speedRaw)

    fun displayedRpm(): Float = benchRpm ?: snapshot.rpm
}
