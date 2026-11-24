package com.shilapi.xcertplay.media

/**
 * Diagnostic link between the touch uplink and the video stream, read by [VideoStats].
 *
 * Answers one question: how long the first frame after a touch takes ([onFrame] return value,
 * printed as touch2frame).
 */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile private var pendingRenderTouchNs = 0L
    @Volatile var maxSendNs = 0L

    /**
     * Starts the latency window when the controller accepts a touch, not when the touch-writer
     * thread eventually gets to it: the controller queue used to make the send-side measurement
     * look healthy while the picture was already hundreds of milliseconds behind.
     */
    fun onTouchQueued() {
        if (pendingTouchNs == 0L) pendingTouchNs = System.nanoTime()
        if (pendingRenderTouchNs == 0L) pendingRenderTouchNs = pendingTouchNs
    }

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (pendingRenderTouchNs == 0L) pendingRenderTouchNs = pendingTouchNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-first-received-frame latency, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }

    /**
     * Returns touch-to-first-rendered-frame latency, or -1. This is the number that matches what
     * the driver sees; [onFrame] stops at packet arrival and cannot see decoder/display latency.
     */
    fun onRendered(nowNs: Long): Long {
        val touch = pendingRenderTouchNs
        if (touch == 0L) return -1
        pendingRenderTouchNs = 0L
        return nowNs - touch
    }
}
