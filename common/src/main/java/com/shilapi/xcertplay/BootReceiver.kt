package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Starts the CarPlay host after boot or STR wake-up when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val nowMs = SystemClock.elapsedRealtime()
        val hasSession = CarPlayBackgroundSession.hasSession()
        val lastAt = lastCustomBootAtMs.get()
        if (!BootBroadcastGuard.allowBootLaunch(action, hasSession, nowMs, lastAt)) {
            if (BootBroadcastGuard.isCustomBootAction(action)) {
                Log.w(TAG, "Ignored custom boot action $action (activeSession=$hasSession elapsed=${nowMs}ms)")
            }
            return
        }
        if (BootBroadcastGuard.isCustomBootAction(action) && !lastCustomBootAtMs.compareAndSet(lastAt, nowMs)) {
            return
        }
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            // The home page starts the session; this flag makes it move to the CarPlay screen as
            // soon as the session becomes active. Without it the boot path connected in the
            // background and stayed on the pre-connection home page until the app was killed.
            putExtra("auto_open_carplay", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        val lastCustomBootAtMs = AtomicLong(-1L)
    }
}
