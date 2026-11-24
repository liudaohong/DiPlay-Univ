package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.daynight.CarDataMonitor
import com.shilapi.xcertplay.daynight.CarLampMonitor

/**
 * adb entry points for [BenchCluster]:
 *
 *   adb shell am broadcast -n <package>/com.shilapi.xcertplay.BenchClusterReceiver \
 *       --ez enabled true
 *
 *   adb shell am broadcast -n <package>/com.shilapi.xcertplay.BenchClusterReceiver \
 *       -a com.shilapi.xcertplay.BENCH_SWIPE --es direction left
 *
 *   adb shell am broadcast -n <package>/com.shilapi.xcertplay.BenchClusterReceiver \
 *       -a com.shilapi.xcertplay.BENCH_LAMP --es state night|day|toggle|auto
 *
 *   adb shell am broadcast -n <package>/com.shilapi.xcertplay.BenchClusterReceiver \
 *       -a com.shilapi.xcertplay.BENCH_THEME --es theme next|prev|default|ray|daynight|math
 *
 * The receiver only flips the in-memory switch / forwards the swipe / overrides the 日夜 theme.
 * It cannot start a session, change Wi-Fi, audio or pairing; with no running CarPlayHostActivity
 * the swipe is a no-op.
 */
class BenchClusterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.shilapi.xcertplay.BENCH_SEED_THEME" -> {
                val ok = CustomThemeRepository.seedSample(context)
                Log.i("DiPlay-Bench", "seed test theme ok=$ok path=${CustomThemeRepository.themesDir(context)}")
                return
            }
            "com.shilapi.xcertplay.BENCH_CLUSTER_CONTENT" -> {
                val view = intent.getStringExtra("view") ?: "mirror"
                AirPlayPersistence.saveClusterInitialView(context, view)
                Log.i("DiPlay-Bench", "bench cluster content view=$view")
                return
            }
            BenchCluster.ACTION -> BenchCluster.setEnabled(
                intent.getBooleanExtra(BenchCluster.EXTRA_ENABLED, true),
                "adb-broadcast",
            )

            BenchCluster.ACTION_SWIPE -> {
                val direction = intent.getStringExtra(BenchCluster.EXTRA_DIRECTION)?.lowercase()
                if (direction == "left" || direction == "right") {
                    BenchCluster.requestSwipe(direction)
                } else {
                    Log.w("DiPlay-Bench", "ignored swipe direction=$direction")
                }
            }

            BenchCluster.ACTION_LAMP -> {
                val state = intent.getStringExtra(BenchCluster.EXTRA_LAMP_STATE)?.lowercase()
                val night = if (state == "toggle") {
                    !(CarLampMonitor.effectiveNight ?: false)
                } else {
                    when (state) {
                        "night", "on", "1" -> true
                        "day", "off", "0" -> false
                        "auto", "clear", "reset", null -> null
                        else -> {
                            Log.w("DiPlay-Bench", "ignored lamp state=$state")
                            return
                        }
                    }
                }
                CarLampMonitor.setBenchNight(night)
                Log.i("DiPlay-Bench", "bench lamp=${night?.let { if (it) "night" else "day" } ?: "auto"}")
            }

            BenchCluster.ACTION_DATA -> {
                CarDataMonitor.start(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    val s = CarDataMonitor.snapshot
                    Log.i(
                        "DiPlay-Bench",
                        "vehicle data speedRaw=${s.speedRaw} speedKmh=${CarDataMonitor.speedKmh()} " +
                            "rpm=${s.rpm} odoKm=${s.odometerKm} rangeKm=${s.rangeKm} " +
                            "connected=${s.connected} error=${s.error}",
                    )
                    CarDataMonitor.stop()
                }, 2500L)
            }

            BenchCluster.ACTION_VEHICLE -> {
                if (intent.getBooleanExtra(BenchCluster.EXTRA_CLEAR_VEHICLE, false)) {
                    CarDataMonitor.clearBenchVehicleData()
                }
                if (intent.hasExtra(BenchCluster.EXTRA_SPEED_KMH)) {
                    CarDataMonitor.setBenchSpeedKmh(
                        intent.getFloatExtra(BenchCluster.EXTRA_SPEED_KMH, Float.NaN),
                    )
                }
                if (intent.hasExtra(BenchCluster.EXTRA_RPM)) {
                    CarDataMonitor.setBenchRpm(
                        intent.getFloatExtra(BenchCluster.EXTRA_RPM, Float.NaN),
                    )
                }
                Log.i(
                    "DiPlay-Bench",
                    "vehicle override speed=${CarDataMonitor.displayedSpeedKmh()} " +
                        "rpm=${CarDataMonitor.displayedRpm()}",
                )
            }

            BenchCluster.ACTION_DEMO_INFO -> {
                if (intent.getBooleanExtra("clear", false)) {
                    BenchCluster.demoPorthole = null
                    Log.i("DiPlay-Bench", "bench demo info cleared")
                } else {
                    val demo = BenchCluster.DemoPorthole(
                        arrivalClock = intent.getStringExtra(BenchCluster.EXTRA_DEMO_CLOCK) ?: "21:40",
                        deltaText = intent.getStringExtra(BenchCluster.EXTRA_DEMO_DELTA) ?: "+2 分",
                        deltaLate = intent.getBooleanExtra(BenchCluster.EXTRA_DEMO_LATE, false),
                        remainingKm = intent.getStringExtra(BenchCluster.EXTRA_DEMO_KM) ?: "8.4 km",
                        remainingMin = intent.getStringExtra(BenchCluster.EXTRA_DEMO_MIN) ?: "12 分钟",
                        songTitle = intent.getStringExtra(BenchCluster.EXTRA_DEMO_SONG)
                            ?: "我也可畅游异国放心吃喝",
                        songArtist = intent.getStringExtra(BenchCluster.EXTRA_DEMO_ARTIST) ?: "再见二丁目",
                    )
                    BenchCluster.demoPorthole = demo
                    Log.i("DiPlay-Bench", "bench demo info=$demo")
                }
                BenchCluster.demoListener?.invoke()
            }

            BenchCluster.ACTION_THEME -> {
                if (ThemeSwitchGate.inProgress) {
                    Log.w("DiPlay-Bench", "ignored theme change while switching")
                    return
                }
                val requested = intent.getStringExtra(BenchCluster.EXTRA_THEME)
                    ?.trim()
                    ?.lowercase()
                val currentId = AirPlayPersistence.loadClusterPortholeTheme(context)
                val nextId = when (requested) {
                    null, "", "next", "left" ->
                        CustomThemeRepository.nextSelectionId(context, currentId, 1)
                    "prev", "previous", "right" ->
                        CustomThemeRepository.nextSelectionId(context, currentId, -1)
                    else -> {
                        val builtIn = PortholeTheme.entries.firstOrNull { it.id == requested }
                        when {
                            builtIn != null -> builtIn.id
                            CustomThemeRepository.exists(context, requested) -> requested
                            else -> null
                        }
                    }
                }
                if (nextId == null) {
                    Log.w("DiPlay-Bench", "ignored theme=$requested path=${CustomThemeRepository.themesDir(context)}")
                    return
                }
                AirPlayPersistence.saveClusterPortholeTheme(context, nextId)
                Log.i("DiPlay-Bench", "bench theme $currentId -> $nextId request=$requested")
            }
        }
    }
}
