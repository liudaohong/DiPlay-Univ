package com.shilapi.xcertplay.media

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap

/**
 * Frames the platform compositor actually reports as rendered to the decoder's output surface.
 *
 * Window FrameMetrics cannot see a SurfaceView's video layer. MediaCodec's
 * OnFrameRenderedListener is the public callback that does, so this is the honest "displayed fps"
 * for the main picture (and optionally the instrument streams).
 */
internal object DisplayedVideoStats {
    private const val WINDOW_NS = 1_000_000_000L

    private data class Window(var startNs: Long = 0L, var frames: Int = 0)

    private val windows = ConcurrentHashMap<Int, Window>()
    private val rates = ConcurrentHashMap<Int, Double>()
    private val updatedAtMs = ConcurrentHashMap<Int, Long>()

    /** Dedicated callback thread: 60 callbacks/s must not be posted onto the UI thread. */
    val handler: Handler by lazy {
        val thread = HandlerThread("carplay-frame-rendered").apply { start() }
        Handler(thread.looper)
    }

    fun onFrameRendered(streamType: Int) {
        val now = System.nanoTime()
        val window = windows.getOrPut(streamType) { Window() }
        synchronized(window) {
            if (window.startNs == 0L) window.startNs = now
            window.frames++
            val elapsed = now - window.startNs
            if (elapsed >= WINDOW_NS) {
                rates[streamType] = window.frames * 1_000_000_000.0 / elapsed
                updatedAtMs[streamType] = now / 1_000_000L
                window.startNs = now
                window.frames = 0
            }
        }
    }

    fun fps1s(streamType: Int): Double = rates[streamType] ?: -1.0

    fun ageMs(streamType: Int): Long {
        val at = updatedAtMs[streamType] ?: return Long.MAX_VALUE
        return System.nanoTime() / 1_000_000L - at
    }
}
