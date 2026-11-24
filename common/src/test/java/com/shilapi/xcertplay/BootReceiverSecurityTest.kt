package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverSecurityTest {
    @Test
    fun systemBootCompletedIsAlwaysAllowed() {
        assertTrue(BootBroadcastGuard.allowBootLaunch(Intent.ACTION_BOOT_COMPLETED, hasActiveSession = true, nowElapsedMs = 600_000L, lastCustomBootAtMs = 599_000L))
    }

    @Test
    fun customBootAllowsOvernightStrWakeupButBlocksActiveDriveAndRapidFlood() {
        // Overnight STR wake-up (12 hours elapsedRealtime, no active session) MUST be allowed!
        val twelveHoursMs = 12L * 3_600_000L
        assertTrue(BootBroadcastGuard.allowBootLaunch(BootBroadcastGuard.ACTION_BOOT_HU, hasActiveSession = false, nowElapsedMs = twelveHoursMs, lastCustomBootAtMs = -1L))
        // Next day STR wake-up (24 hours after previous wake-up) MUST also be allowed!
        assertTrue(BootBroadcastGuard.allowBootLaunch(BootBroadcastGuard.ACTION_BOOT_HU, hasActiveSession = false, nowElapsedMs = twelveHoursMs * 3, lastCustomBootAtMs = twelveHoursMs))

        // Mid-drive spoofing while CarPlay session is active is blocked.
        assertFalse(BootBroadcastGuard.allowBootLaunch(BootBroadcastGuard.ACTION_BOOT_HU, hasActiveSession = true, nowElapsedMs = twelveHoursMs + 120_000L, lastCustomBootAtMs = -1L))

        // Rapid flood within 60s cooldown is blocked.
        assertFalse(BootBroadcastGuard.allowBootLaunch(BootBroadcastGuard.ACTION_BOOT_HU, hasActiveSession = false, nowElapsedMs = twelveHoursMs + 10_000L, lastCustomBootAtMs = twelveHoursMs))
    }

    @Test
    fun bluetoothRestoreSupportsStrAndOrderedShutdownOnlyWhenPending() {
        assertTrue(BootBroadcastGuard.allowBluetoothBootRestore(BootBroadcastGuard.ACTION_BOOT_HU, pendingRestore = true))
        assertFalse(BootBroadcastGuard.allowBluetoothBootRestore(BootBroadcastGuard.ACTION_BOOT_HU, pendingRestore = false))

        assertTrue(BootBroadcastGuard.allowShutdownAction(Intent.ACTION_SHUTDOWN, pendingRestore = false))
        assertTrue(BootBroadcastGuard.allowShutdownAction(BootBroadcastGuard.ACTION_SHUTDOWN_HU, pendingRestore = true))
        assertFalse(BootBroadcastGuard.allowShutdownAction(BootBroadcastGuard.ACTION_SHUTDOWN_HU, pendingRestore = false))
    }
}
