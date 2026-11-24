package com.shilapi.xcertplay.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Automatic Bluetooth switching around a CarPlay session ("自动开关蓝牙" in settings).
 *
 * The head unit runs Android 9 (API 28), where [BluetoothAdapter.enable] / [BluetoothAdapter.disable]
 * take effect directly with only the normal BLUETOOTH + BLUETOOTH_ADMIN permissions; no
 * confirmation dialog is involved (see 交接-蓝牙开关-20261112.md). On Android 13+ systems the
 * platform ignores those calls for a normal app, so we log and leave the radio alone instead of
 * surprising the driver with a system dialog.
 *
 * State changes are asynchronous; this helper only *requests* the change and logs the queued
 * result. The session callbacks are the source of truth for "should Bluetooth be on now".
 */
object AutoBluetooth {
    private const val TAG = "DiPlay-AutoBt"
    private const val PREFS = "auto_bluetooth_restore"
    private const val KEY_PENDING = "pending"

    /** Requests within this window are collapsed; the radio itself needs seconds to switch. */
    private const val DEBOUNCE_MS = 4_000L

    @Volatile private var lastOnAt = 0L
    @Volatile private var lastOffAt = 0L
    /** Separate throttle for "waiting for the system" notes, so waiting never blocks a request. */
    @Volatile private var lastOnWaitLogAt = 0L
    @Volatile private var lastOffWaitLogAt = 0L

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** True when the last auto-Bluetooth action was an "off" and no "on" has completed since. */
    fun pendingRestore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)

    private fun setPendingRestore(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, pending).apply()
    }

    /** Turn Bluetooth on before a connection attempt or after the session ends. */
    fun ensureOn(context: Context, reason: String, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        val adapter = adapter(app)
        if (adapter == null) {
            emit(log, "AutoBluetooth: 本机没有蓝牙适配器（$reason）")
            return
        }
        if (!hasPermission(app)) {
            emit(log, "AutoBluetooth: 缺少 BLUETOOTH_CONNECT 权限（$reason）")
            return
        }
        if (adapter.isEnabled) {
            setPendingRestore(app, false)
            return
        }
        setPendingRestore(app, false)
        val now = SystemClock.elapsedRealtime()
        if (now - lastOnAt < DEBOUNCE_MS) return
        if (adapter.state != BluetoothAdapter.STATE_OFF) {
            // The radio is still switching. Do not stamp lastOnAt here: a request made while
            // STATE_TURNING_OFF must still queue as soon as the radio reaches STATE_OFF.
            if (now - lastOnWaitLogAt >= DEBOUNCE_MS) {
                lastOnWaitLogAt = now
                emit(log, "AutoBluetooth: 蓝牙状态=${stateName(adapter.state)}，等系统完成（$reason）")
            }
            return
        }
        lastOnAt = now
        // A real state change resets the opposite debounce: after an off/on cycle (quick
        // reconnect) the following legitimate off must not be swallowed by the old timestamp.
        lastOffAt = 0L
        @Suppress("DEPRECATION")
        val queued = runCatching { adapter.enable() }.getOrDefault(false)
        emit(log, "AutoBluetooth: 请求打开蓝牙 -> $queued（$reason）")
        if (queued) setPendingRestore(app, false)
        if (!queued) emit(log, "AutoBluetooth: 系统拒绝直接开启（Android 13+ 可能只允许用户手动打开）")
    }

    /** Turn Bluetooth off once the CarPlay main screen is visible. */
    fun ensureOff(context: Context, reason: String, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        val adapter = adapter(app)
        if (adapter == null) {
            emit(log, "AutoBluetooth: 本机没有蓝牙适配器（$reason）")
            return
        }
        if (!hasPermission(app)) {
            emit(log, "AutoBluetooth: 缺少 BLUETOOTH_CONNECT 权限（$reason）")
            return
        }
        if (!adapter.isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOffAt < DEBOUNCE_MS) return
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            if (now - lastOffWaitLogAt >= DEBOUNCE_MS) {
                lastOffWaitLogAt = now
                emit(log, "AutoBluetooth: 蓝牙状态=${stateName(adapter.state)}，等系统完成（$reason）")
            }
            return
        }
        lastOffAt = now
        lastOnAt = 0L
        // Remember that WE turned the radio off. If the head unit cuts power before the app can
        // react, the boot receiver uses this to put Bluetooth back on at the next ignition.
        setPendingRestore(app, true)
        @Suppress("DEPRECATION")
        val queued = runCatching { adapter.disable() }.getOrDefault(false)
        emit(log, "AutoBluetooth: 请求关闭蓝牙 -> $queued（$reason）")
        if (!queued) emit(log, "AutoBluetooth: 系统拒绝直接关闭（Android 13+ 可能只允许用户手动关闭）")
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun stateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "state=$state"
    }

    private fun emit(log: (String) -> Unit, message: String) {
        Log.i(TAG, message)
        runCatching { log(message) }
    }
}
