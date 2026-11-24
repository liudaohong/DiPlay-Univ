package com.shilapi.xcertplay

import android.util.Log

/**
 * Opt-in bench switch: show the CarPlay instrument-cluster window without the head unit's
 * incall projection callback, and let adb drive the same three-finger swipe handler.
 *
 * OFF by default and only ever changed by the explicit adb broadcast. The production path
 * (InCallClusterBridge, activation sequence, touch gesture) never reads or changes it, so a
 * normal car session behaves exactly as before. The flag is in-memory on purpose: a process
 * restart returns to the normal behaviour.
 */
object BenchCluster {
    const val ACTION = "com.shilapi.xcertplay.BENCH_CLUSTER"
    const val ACTION_SWIPE = "com.shilapi.xcertplay.BENCH_SWIPE"
    const val ACTION_LAMP = "com.shilapi.xcertplay.BENCH_LAMP"
    const val ACTION_THEME = "com.shilapi.xcertplay.BENCH_THEME"
    const val ACTION_DATA = "com.shilapi.xcertplay.BENCH_DATA"
    const val ACTION_VEHICLE = "com.shilapi.xcertplay.BENCH_VEHICLE"
    const val ACTION_DEMO_INFO = "com.shilapi.xcertplay.BENCH_DEMO_INFO"
    const val EXTRA_SPEED_KMH = "speed_kmh"
    const val EXTRA_RPM = "rpm"
    const val EXTRA_CLEAR_VEHICLE = "clear"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_LAMP_STATE = "state"
    const val EXTRA_THEME = "theme"
    const val EXTRA_DEMO_CLOCK = "clock"
    const val EXTRA_DEMO_DELTA = "delta"
    const val EXTRA_DEMO_LATE = "late"
    const val EXTRA_DEMO_KM = "km"
    const val EXTRA_DEMO_MIN = "min"
    const val EXTRA_DEMO_SONG = "song"
    const val EXTRA_DEMO_ARTIST = "artist"

    /**
     * Fake arrival/song data for developing the custom-theme info cards without CarPlay.
     *
     * The PC bench has no route guidance and usually no Now Playing stream, so the ETA/song
     * cards would only ever show their placeholders. `bench.cmd demo ...` fills this in and the
     * host pushes it through the exact same `updatePorthole()` path the real data uses.
     */
    data class DemoPorthole(
        val arrivalClock: String,
        val deltaText: String,
        val deltaLate: Boolean,
        val remainingKm: String,
        val remainingMin: String,
        val songTitle: String,
        val songArtist: String,
    )

    private const val TAG = "DiPlay-Bench"

    @Volatile
    var enabled: Boolean = false
        private set

    /** Registered by CarPlayHostActivity while it is alive; invoked on the caller's thread. */
    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    /** Same activity, same thread contract; receives "left" or "right". */
    @Volatile
    var swipeListener: ((String) -> Unit)? = null

    /** Registered by CarPlayHostActivity; re-pushes the porthole info after a demo change. */
    @Volatile
    var demoListener: (() -> Unit)? = null

    /** Non-null while the bench fake arrival/song data is in use. */
    @Volatile
    var demoPorthole: DemoPorthole? = null

    fun setEnabled(value: Boolean, source: String) {
        if (enabled == value) return
        enabled = value
        Log.i(TAG, "bench cluster enabled=$value source=$source")
        listener?.invoke(value)
    }

    /**
     * Calls the activity's real [CarPlayHostActivity.swipeClusterMode] path. The touch gesture
     * keeps its own entry point untouched; this is only a second caller for bench testing.
     */
    fun requestSwipe(direction: String) {
        Log.i(TAG, "bench swipe requested direction=$direction")
        swipeListener?.invoke(direction)
    }
}
