package com.shilapi.xcertplay

import android.content.Intent

/**
 * Runtime guard for exported boot/shutdown BroadcastReceivers (SEC-04).
 *
 * Standard Android broadcasts (`ACTION_BOOT_COMPLETED`, `ACTION_SHUTDOWN`) are protected by the OS
 * (`<protected-broadcast>`), so unprivileged apps cannot forge them. Vendor/custom head-unit actions
 * (`ACTION_BOOT_HU`, `QUICKBOOT_POWERON`, `ACTION_SHUTDOWN_HU`) may not be declared protected on
 * every head-unit ROM.
 *
 * Important UNI-V / Feiyu OS STR (Suspend-to-RAM) constraint:
 *  - Overnight parking uses STR where `SystemClock.elapsedRealtime()` keeps counting (hours/days)
 *    and the process stays alive in memory, so a fixed "3 minutes since cold boot" cutoff would
 *    break next-morning STR wake-up auto-start.
 *  - Instead, custom boot launch is blocked whenever CarPlay already has an active session (so a
 *    third-party app cannot hijack an active drive) and rate-limited by a cooldown window.
 *  - Custom HU shutdown (`ACTION_SHUTDOWN_HU`) fires during a ~2.5 s ordered broadcast BEFORE the
 *    display goes completely off, so it is gated on `pendingRestore` (only restoring Bluetooth if
 *    DiPlay itself turned it off) rather than screen-off state.
 */
internal object BootBroadcastGuard {
    /** Minimum interval between custom head-unit boot auto-launches. */
    const val CUSTOM_BOOT_COOLDOWN_MS = 60_000L

    const val ACTION_BOOT_HU = "android.intent.action.ACTION_BOOT_HU"
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    const val ACTION_SHUTDOWN_HU = "android.intent.action.ACTION_SHUTDOWN_HU"

    fun isCustomBootAction(action: String?): Boolean =
        action == ACTION_BOOT_HU || action == ACTION_QUICKBOOT_POWERON

    fun allowBootLaunch(
        action: String?,
        hasActiveSession: Boolean,
        nowElapsedMs: Long,
        lastCustomBootAtMs: Long,
    ): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> true
        ACTION_BOOT_HU, ACTION_QUICKBOOT_POWERON ->
            // Block hijacking an active drive; allow cold boot & STR wake-up after cooldown.
            !hasActiveSession && (
                lastCustomBootAtMs < 0L ||
                    nowElapsedMs < lastCustomBootAtMs ||
                    (nowElapsedMs - lastCustomBootAtMs) >= CUSTOM_BOOT_COOLDOWN_MS
                )
        else -> false
    }

    fun allowBluetoothBootRestore(action: String?, pendingRestore: Boolean): Boolean =
        pendingRestore && (action == Intent.ACTION_BOOT_COMPLETED || isCustomBootAction(action))

    fun allowShutdownAction(action: String?, pendingRestore: Boolean): Boolean = when (action) {
        Intent.ACTION_SHUTDOWN -> true
        // Only act on custom HU shutdown when DiPlay itself turned Bluetooth off.
        ACTION_SHUTDOWN_HU -> pendingRestore
        else -> false
    }
}
