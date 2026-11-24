package com.shilapi.xcertplay

import android.content.Intent

/**
 * Runtime guard for exported boot/shutdown BroadcastReceivers (SEC-04).
 *
 * Standard Android broadcasts (`ACTION_BOOT_COMPLETED`, `ACTION_SHUTDOWN`) are protected by the OS
 * (`<protected-broadcast>`), so unprivileged apps cannot forge them. Vendor/custom head-unit actions
 * (`ACTION_BOOT_HU`, `QUICKBOOT_POWERON`, `ACTION_SHUTDOWN_HU`) may not be declared protected on
 * every head-unit ROM, allowing a third-party app to send them while driving.
 */
internal object BootBroadcastGuard {
    /** Custom head-unit boot actions are only honored within 3 minutes of OS/STR boot. */
    const val CUSTOM_BOOT_WINDOW_MS = 180_000L

    const val ACTION_BOOT_HU = "android.intent.action.ACTION_BOOT_HU"
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    const val ACTION_SHUTDOWN_HU = "android.intent.action.ACTION_SHUTDOWN_HU"

    fun isCustomBootAction(action: String?): Boolean =
        action == ACTION_BOOT_HU || action == ACTION_QUICKBOOT_POWERON

    fun allowBootAction(
        action: String?,
        elapsedRealtimeMs: Long,
        alreadyHandledCustomBoot: Boolean,
    ): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> true
        ACTION_BOOT_HU, ACTION_QUICKBOOT_POWERON ->
            !alreadyHandledCustomBoot && elapsedRealtimeMs in 0L..CUSTOM_BOOT_WINDOW_MS
        else -> false
    }

    fun allowShutdownAction(
        action: String?,
        pendingRestore: Boolean,
        isScreenInteractive: Boolean,
    ): Boolean = when (action) {
        Intent.ACTION_SHUTDOWN -> true
        // Custom HU shutdown is only honored when DiPlay actually turned Bluetooth off AND the
        // head-unit screen is already non-interactive (ACC off), blocking mid-drive spoofing.
        ACTION_SHUTDOWN_HU -> pendingRestore && !isScreenInteractive
        else -> false
    }
}
