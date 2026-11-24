package com.shilapi.xcertplay.daynight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log

/**
 * Fast vehicle-data poller (speed/RPM/odometer/range) over the same vendor-whitelisted
 * [LampReaderService] that already supplies the headlamp state.
 *
 * This deliberately does not create a second Car connection: the service runs in
 * `com.wt.n2048`, where android.car's property manager is readable, and these properties
 * are floats. The lamp poller stays at 1s; speed/RPM need 100-200ms, so this is a separate
 * 150ms tick bound only while the PC bench MCU view is attached.
 */
object CarDataMonitor {
    private const val TAG = "CarDataMonitor"
    private const val POLL_MS = 100L
    private const val REBIND_MS = 5000L

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
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var running = false
    private var clients = 0
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
            if (!running) {
                runCatching { appContext?.unbindService(this) }
                return
            }
            reader = service?.let { ILampReader.Stub.asInterface(it) }
            binding = false
            Log.i(TAG, "car data probe connected process=com.wt.n2048")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reader = null
            binding = false
            bindRequested = false
            if (running) Log.i(TAG, "car data probe disconnected")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            pollOnce()
            if (running) handler?.postDelayed(this, POLL_MS)
        }
    }

    /** Call from the bench MCU view's onAttachedToWindow. */
    fun start(context: Context) {
        clients++
        if (running) return
        val app = context.applicationContext
        appContext = app
        running = true
        snapshot = State(error = "waiting for probe")
        Log.i(TAG, "car data monitor start (poll=${POLL_MS}ms)")
        val thread = HandlerThread("car-data-monitor").apply { start() }
        worker = thread
        val nextHandler = Handler(thread.looper)
        handler = nextHandler
        bindProbe(app, force = true)
        nextHandler.post(tick)
    }

    /** Call from the bench MCU view's onDetachedFromWindow. */
    fun stop() {
        if (clients > 0) clients--
        if (clients > 0 || !running) return
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
        Log.i(TAG, "car data monitor stop")
    }

    private fun bindProbe(ctx: Context, force: Boolean) {
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
        val ctx = appContext ?: return
        val r = reader
        if (r == null) {
            bindProbe(ctx, force = false)
            snapshot = State(error = "probe not bound")
            return
        }
        val next = try {
            State(
                speedRaw = r.getSpeed(),
                rpm = r.getRpm(),
                odometerKm = r.getOdometer(),
                rangeKm = r.getRange(),
                gear = r.gear,
                gearRaw = r.gearRaw,
                gearText = r.gearText ?: "",
                turnLeft = r.isTurnLeftRequested,
                turnRight = r.isTurnRightRequested,
                hazard = r.isHazardRequested,
                turnLeftBlink = r.isTurnLeftBlink,
                turnRightBlink = r.isTurnRightBlink,
                connected = r.isConnected,
                error = r.lastError ?: "",
            )
        } catch (e: RemoteException) {
            reader = null
            binding = false
            bindRequested = false
            snapshot = State(error = "remote read failed: ${e.javaClass.simpleName}")
            return
        }
        snapshot = next

        val now = SystemClock.elapsedRealtime()
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
