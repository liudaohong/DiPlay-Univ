package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBacklogRecoveryTest {
    @Test
    fun ignoresShortDelayedBurstAndRecoversSustainedGrowingBacklog() {
        val recovery = VideoBacklogRecovery()
        val ms = 1_000_000L

        // Momentary Wi-Fi burst (age 300ms, span 120ms) starts grace timer but does not recover immediately.
        assertFalse(recovery.observe(nowNs = 1_000 * ms, currentReceivedNs = 700 * ms, pendingFrames = 5, newestPendingReceivedNs = 820 * ms))
        // Still within grace window (500ms later).
        assertFalse(recovery.observe(nowNs = 1_500 * ms, currentReceivedNs = 1_100 * ms, pendingFrames = 7, newestPendingReceivedNs = 1_280 * ms))
        // Past 750ms grace window with growing pending frames / span -> trigger recovery.
        assertTrue(recovery.observe(nowNs = 1_800 * ms, currentReceivedNs = 1_300 * ms, pendingFrames = 10, newestPendingReceivedNs = 1_560 * ms))
    }

    @Test
    fun recoversImmediatelyOnHardAgeLimit() {
        val recovery = VideoBacklogRecovery()
        val ms = 1_000_000L
        assertTrue(recovery.observe(nowNs = 2_000 * ms, currentReceivedNs = 400 * ms, pendingFrames = 6, newestPendingReceivedNs = 550 * ms))
    }
}
