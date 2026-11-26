package com.shilapi.xcertplay.daynight

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.Locale

/**
 * Changan UNI-V wheel-speed and gear source for iAP2 `$PASCD` dead reckoning in tunnels.
 *
 * Reads live vehicle telemetry from the shared [CarDataMonitor] motion-only AIDL snapshot
 * (via `com.wt.n2048` / [LampReaderService]), requiring zero ADB permissions.
 */
class ChanganVehicleSpeedSource internal constructor(
    private val startMonitor: () -> Unit,
    private val stopMonitor: () -> Unit,
    private val registerListener: ((Long, CarDataMonitor.State) -> Unit) -> Unit,
    private val unregisterListener: ((Long, CarDataMonitor.State) -> Unit) -> Unit,
    private val minSampleIntervalMillis: Long = DEFAULT_MIN_SAMPLE_INTERVAL_MS,
    private val maxSamples: Int = MAX_SAMPLES,
) : VehicleSpeedSource {

    constructor(context: Context) : this(
        startMonitor = { CarDataMonitor.startMotionOnly(context.applicationContext) },
        stopMonitor = { CarDataMonitor.stopMotionOnly() },
        registerListener = { CarDataMonitor.addSampleListener(it) },
        unregisterListener = { CarDataMonitor.removeSampleListener(it) },
    )

    private val samples = ArrayList<VehicleSpeedSample>()
    private var started = false
    private var gear: VehicleGear? = null
    private var lastSampleElapsedMs = -1L
    private var firstSampleLogged = false
    private val listener: (Long, CarDataMonitor.State) -> Unit = { elapsedMillis, state ->
        onSample(elapsedMillis, state)
    }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        samples.clear()
        gear = null
        lastSampleElapsedMs = -1L
        firstSampleLogged = false
        registerListener(listener)
        startMonitor()
        Log.i(TAG, "changan wheel speed started")
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        unregisterListener(listener)
        samples.clear()
        gear = null
        lastSampleElapsedMs = -1L
        stopMonitor()
        Log.i(TAG, "changan wheel speed stopped")
    }

    @Synchronized
    internal fun onSample(elapsedMillis: Long, state: CarDataMonitor.State) {
        if (!started || !state.connected) return
        val kmh = CarDataMonitor.speedKmh(state.speedRaw).toDouble()
        if (!kmh.isFinite() || kmh !in 0.0..MAX_VALID_SPEED_KMH) return

        mapGear(state.gear)?.let { mapped ->
            if (gear != mapped) {
                gear = mapped
                Log.i(TAG, "gear $mapped (raw=${state.gearRaw}/${state.gearText})")
            }
        }

        if (lastSampleElapsedMs >= 0L &&
            elapsedMillis >= lastSampleElapsedMs &&
            (elapsedMillis - lastSampleElapsedMs) < minSampleIntervalMillis
        ) {
            return
        }

        lastSampleElapsedMs = elapsedMillis
        if (samples.size >= maxSamples) {
            samples.removeAt(0)
        }
        samples += VehicleSpeedSample(elapsedMillis, kmh / 3.6)
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first vehicle speed ${"%.1f".format(Locale.US, kmh)} km/h")
        }
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        if (samples.isEmpty()) return null
        val effectiveGear = gear
            ?: if (samples.last().metersPerSecond * 3.6 >= MOVING_DRIVE_FALLBACK_KMH) {
                VehicleGear.DRIVE
            } else {
                samples.clear()
                return null
            }
        return VehicleSpeedReading(effectiveGear, samples.toList()).also { samples.clear() }
    }

    companion object {
        private const val TAG = "DiPlay-CA-Speed"
        private const val DEFAULT_MIN_SAMPLE_INTERVAL_MS = 200L
        private const val MAX_SAMPLES = 40
        private const val MAX_VALID_SPEED_KMH = 300.0
        private const val MOVING_DRIVE_FALLBACK_KMH = 1.0

        /**
         * Maps canonical gear (0=unknown, 1=P, 2=R, 3=N, 4=D, 5..10=M1..M6, 11..12 reserved)
         * into iAP2 `$PASCD` [VehicleGear].
         */
        fun mapGear(canonicalGear: Int): VehicleGear? = when (canonicalGear) {
            1 -> VehicleGear.PARK
            2 -> VehicleGear.REVERSE
            3 -> VehicleGear.NEUTRAL
            in 4..12 -> VehicleGear.DRIVE
            else -> null
        }
    }
}
