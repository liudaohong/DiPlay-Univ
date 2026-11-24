package com.shilapi.xcertplay

/**
 * Stable preference values; never persist enum ordinals.
 *
 * SYSTEM reads Configuration.uiMode. The old ambient-light option was removed because this head
 * unit has no TYPE_LIGHT sensor, so it could only fall back to the system value. LAMP is different:
 * it reads the car's own headlamp property (0x11400E10) with Settings.System "car_mode" as the
 * fallback, both verified on this unit in handover/车灯昼夜同步_移植交接清单.md.
 */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    DAY("day"),
    NIGHT("night"),

    /** Follow the vehicle headlamps: on -> dark, off -> light. */
    LAMP("lamp");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed. */
internal class CarPlayNightModeController(
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
) {
    var night: Boolean = initialNight
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight
    private var lampNight: Boolean? = null

    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
        lampNight: Boolean? = null,
    ) {
        this.mode = mode
        this.systemNight = systemNight
        this.lampNight = lampNight
        applyMode()
    }

    fun systemChanged(night: Boolean) {
        systemNight = night
        if (mode == CarPlayNightMode.SYSTEM) applyNight(night)
    }

    /** The headlamp poller read a new value; only acts while LAMP is the selected mode. */
    fun lampChanged(night: Boolean) {
        lampNight = night
        if (mode == CarPlayNightMode.LAMP) applyNight(night)
    }

    private fun applyMode() {
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight)
            CarPlayNightMode.DAY -> applyNight(false)
            CarPlayNightMode.NIGHT -> applyNight(true)
            CarPlayNightMode.LAMP -> {
                // No signal yet: keep the current appearance instead of guessing.
                val lamp = lampNight ?: return
                applyNight(lamp)
            }
        }
    }

    private fun applyNight(value: Boolean) {
        if (value == night) return
        night = value
        onNightChanged(value)
    }
}
