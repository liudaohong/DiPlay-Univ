package com.shilapi.xcertplay.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.BootBroadcastGuard
import java.util.concurrent.atomic.AtomicBoolean

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
        val bootCandidate = action == Intent.ACTION_BOOT_COMPLETED ||
            BootBroadcastGuard.isCustomBootAction(action)
        val shutdownCandidate = action == Intent.ACTION_SHUTDOWN ||
            action == BootBroadcastGuard.ACTION_SHUTDOWN_HU
        if (!bootCandidate && !shutdownCandidate) return
        if (!AirPlayPersistence.loadAutoBluetooth(context)) return

        val app = context.applicationContext
        val pending = AutoBluetooth.pendingRestore(app)
        if (shutdownCandidate) {
            val interactive = runCatching {
                app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            }.getOrDefault(true)
            if (!BootBroadcastGuard.allowShutdownAction(action, pending, interactive)) {
                Log.w(TAG, "Ignored untrusted shutdown broadcast $action (pending=$pending interactive=$interactive)")
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

        val elapsedMs = SystemClock.elapsedRealtime()
        if (!BootBroadcastGuard.allowBootAction(action, elapsedMs, customBootHandled.get())) {
            Log.w(TAG, "Ignored custom boot action $action outside boot window (elapsed=${elapsedMs}ms)")
            return
        }
        if (BootBroadcastGuard.isCustomBootAction(action) && !customBootHandled.compareAndSet(false, true)) {
            return
        }
        if (pending) {
            Log.i(TAG, "boot ($action) with pending auto-Bluetooth off; restoring")
            AutoBluetooth.ensureOn(app, "boot restore") { Log.i(TAG, it) }
        }
    }

    private companion object {
        const val TAG = "DiPlay-BtRestore"
        val customBootHandled = AtomicBoolean(false)
    }
}
