package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide gate for the synchronous porthole-theme switch.
 *
 * While the dashboard is being cleared and the next theme is loaded, both the
 * two-finger gesture and the settings picker must not start another switch:
 * otherwise the requested theme can be overwritten halfway through and the
 * dashboard ends up showing a mixture of two themes.
 */
object ThemeSwitchGate {
    private val busy = AtomicBoolean(false)

    val inProgress: Boolean
        get() = busy.get()

    fun tryBegin(): Boolean = busy.compareAndSet(false, true)

    fun finish() {
        busy.set(false)
    }
}
