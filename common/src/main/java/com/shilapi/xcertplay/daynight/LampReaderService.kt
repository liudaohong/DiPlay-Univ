package com.shilapi.xcertplay.daynight

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Reads the car headlamp property in the vendor-whitelisted process "com.wt.n2048".
 *
 * Why a separate process: on this head unit the android.car "property" manager only answers the
 * process named "com.wt.n2048" (the reference lamp-status app runs its whole application there).
 * In DiPlay's main process every read returns -1, which is what made the old code fall back to
 * car_mode and follow it with the wrong polarity. The main process binds here and reads over AIDL.
 *
 * The service is declared with android:process="com.wt.n2048" in the mobile manifest. It is only
 * bound while the CarPlay host is visible and "Follow headlights" is selected.
 */
class LampReaderService : Service() {

    private val source = CarLampSource()
    private val handler = Handler(Looper.getMainLooper())
    private val retry = object : Runnable {
        override fun run() {
            if (!source.isConnected) source.connectAsync(this@LampReaderService)
            handler.postDelayed(this, 1000L)
        }
    }

    private val binder = object : ILampReader.Stub() {
        override fun getLamp(): Int = source.readLamp()

        override fun getSpeed(): Float = source.readSpeed()

        override fun getRpm(): Float = source.readRpm()

        override fun getOdometer(): Float = source.readOdometer()

        override fun getRange(): Float = source.readRange()

        override fun getCarMode(): Int = source.readCarMode(this@LampReaderService)

        override fun isConnected(): Boolean = source.isConnected

        override fun getManagerClass(): String = source.managerClass()

        override fun getConnectFailures(): Int = source.connectFailures()

        override fun getLastError(): String = source.lastError()

        override fun getCreateCarMethod(): String = source.createCarMethod()

        override fun getManagerName(): String = source.managerName()

        override fun getGear(): Int = source.readGear()

        override fun getGearRaw(): Int = source.readGearRaw()

        override fun getGearText(): String = source.readGearText()

        override fun isTurnLeftRequested(): Boolean = source.turnLeftRequested()

        override fun isTurnRightRequested(): Boolean = source.turnRightRequested()

        override fun isHazardRequested(): Boolean = source.hazardRequested()

        override fun isTurnLeftBlink(): Boolean = source.turnLeftBlink()

        override fun isTurnRightBlink(): Boolean = source.turnRightBlink()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LampReaderService onCreate")
        source.connectAsync(this)
        handler.postDelayed(retry, 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "LampReaderService bound")
        return binder
    }

    private companion object {
        const val TAG = "LampReaderService"
    }
}
