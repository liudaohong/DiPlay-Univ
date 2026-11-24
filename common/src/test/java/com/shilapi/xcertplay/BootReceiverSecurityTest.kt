package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverSecurityTest {
    @Test
    fun systemBootCompletedIsAlwaysAllowed() {
        assertTrue(BootBroadcastGuard.allowBootAction(Intent.ACTION_BOOT_COMPLETED, 600_000L, false))
        assertTrue(BootBroadcastGuard.allowBootAction(Intent.ACTION_BOOT_COMPLETED, 3_600_000L, true))
    }

    @Test
    fun customHeadUnitBootActionsAreRestrictedToBootWindowAndSingleUse() {
        assertTrue(BootBroadcastGuard.allowBootAction(BootBroadcastGuard.ACTION_BOOT_HU, 45_000L, false))
        assertTrue(BootBroadcastGuard.allowBootAction(BootBroadcastGuard.ACTION_QUICKBOOT_POWERON, 180_000L, false))

        // Mid-drive spoofing after 3 minutes is blocked.
        assertFalse(BootBroadcastGuard.allowBootAction(BootBroadcastGuard.ACTION_BOOT_HU, 180_001L, false))
        assertFalse(BootBroadcastGuard.allowBootAction(BootBroadcastGuard.ACTION_QUICKBOOT_POWERON, 900_000L, false))

        // Replay in the same process is blocked.
        assertFalse(BootBroadcastGuard.allowBootAction(BootBroadcastGuard.ACTION_BOOT_HU, 30_000L, true))
    }

    @Test
    fun customShutdownActionRequiresPendingRestoreAndNonInteractiveScreen() {
        assertTrue(BootBroadcastGuard.allowShutdownAction(Intent.ACTION_SHUTDOWN, pendingRestore = false, isScreenInteractive = true))
        assertTrue(BootBroadcastGuard.allowShutdownAction(BootBroadcastGuard.ACTION_SHUTDOWN_HU, pendingRestore = true, isScreenInteractive = false))

        // Spoofed while driver is looking at an interactive screen -> rejected.
        assertFalse(BootBroadcastGuard.allowShutdownAction(BootBroadcastGuard.ACTION_SHUTDOWN_HU, pendingRestore = true, isScreenInteractive = true))
        assertFalse(BootBroadcastGuard.allowShutdownAction(BootBroadcastGuard.ACTION_SHUTDOWN_HU, pendingRestore = false, isScreenInteractive = false))
    }
}
