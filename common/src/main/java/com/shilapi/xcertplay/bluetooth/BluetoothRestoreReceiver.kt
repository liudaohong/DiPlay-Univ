package com.shilapi.xcertplay.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.BootBroadcastGuard

/**
 * Restores the head-unit Bluetooth after DiPlay's auto-Bluetooth switch turned it off.
 *
 * The car may cut power while CarPlay is still connected, so onSessionEnded/shutdown never run.
 * BOOT_COMPLETED / ACTION_BOOT_HU is the reliable next-ignition (cold boot or STR wake-up) path;
 * ACTION_SHUTDOWN / ACTION_SHUTDOWN_HU is a best-effort last chance before power is removed.
 * Both only act when the user enabled auto-Bluetooth.
 */
class BluetoothRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootCandidate = action == Intent.ACTION_BOOT_COMPLETED ||
            BootBroadcastGuard.isCustomBootAction(action)
        val shutdownCandidate = action == Intent.ACTION_SHUTDOWN ||
            action == BootBroadcastGuard.ACTION_SHUTDOWN_HU
        if (!bootCandidate && !shutdownCandidate) return
        if (!AirPlayPersistence.loadAutoBluetooth(context)) return

        val app = context.applicationContext
        val pending = AutoBluetooth.pendingRestore(app)
        if (shutdownCandidate) {
            if (!BootBroadcastGuard.allowShutdownAction(action, pending)) {
                Log.w(TAG, "Ignored custom shutdown broadcast $action with no pending restore")
                return
            }
            // HU shutdown is an ordered broadcast with a ~2.5 s budget. Keep this path short:
            // one binder request, no file/network IO.
            if (!AutoBluetooth.isEnabled(app)) {
                Log.i(TAG, "shutdown ($action) with Bluetooth off; restoring")
                AutoBluetooth.ensureOn(app, "shutdown broadcast") { Log.i(TAG, it) }
            }
            return
        }

        if (BootBroadcastGuard.allowBluetoothBootRestore(action, pending)) {
            Log.i(TAG, "boot ($action) with pending auto-Bluetooth off; restoring")
            AutoBluetooth.ensureOn(app, "boot restore") { Log.i(TAG, it) }
        }
    }

    private companion object {
        const val TAG = "DiPlay-BtRestore"
    }
}
