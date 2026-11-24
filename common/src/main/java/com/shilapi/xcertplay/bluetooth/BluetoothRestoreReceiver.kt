package com.shilapi.xcertplay.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * Restores the head-unit Bluetooth after DiPlay's auto-Bluetooth switch turned it off.
 *
 * The car may cut power while CarPlay is still connected, so onSessionEnded/shutdown never run.
 * BOOT_COMPLETED is the reliable next-ignition path; ACTION_SHUTDOWN is a best-effort last chance
 * before power is removed. Both only act when the user enabled auto-Bluetooth.
 */
class BluetoothRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val boot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == ACTION_BOOT_HU ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val shutdown = action == Intent.ACTION_SHUTDOWN || action == ACTION_SHUTDOWN_HU
        if (!boot && !shutdown) return
        if (!AirPlayPersistence.loadAutoBluetooth(context)) return
        val app = context.applicationContext
        val pending = AutoBluetooth.pendingRestore(app)
        val enabled = AutoBluetooth.isEnabled(app)
        if (shutdown) {
            // HU shutdown is an ordered broadcast with a ~2.5 s budget. Keep this path short:
            // one binder request, no file/network IO.
            if (!enabled) {
                Log.i(TAG, "shutdown ($action) with Bluetooth off; restoring")
                AutoBluetooth.ensureOn(app, "shutdown broadcast") { Log.i(TAG, it) }
            }
            return
        }
        if (pending) {
            Log.i(TAG, "boot ($action) with pending auto-Bluetooth off; restoring")
            AutoBluetooth.ensureOn(app, "boot restore") { Log.i(TAG, it) }
        }
    }

    private companion object {
        const val TAG = "DiPlay-BtRestore"
        const val ACTION_SHUTDOWN_HU = "android.intent.action.ACTION_SHUTDOWN_HU"
        const val ACTION_BOOT_HU = "android.intent.action.ACTION_BOOT_HU"
    }
}
