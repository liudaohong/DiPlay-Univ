package com.shilapi.xcertplay

/**
 * Pure math for the phase-2 continuous mapping (`bind` on non-text layers).
 *
 * Kept free of Android types so the unit tests can run on the JVM: the value is normalised
 * against `from`, optionally clamped, then mapped onto `to` (or two colors).
 */
internal object ThemeBind {

    /** Automatically clamped to 0..1 unless the layer explicitly says `clamp: false`. */
    fun fraction(bind: CustomBindSpec, value: Float): Float? {
        if (!value.isFinite()) return null
        val span = bind.fromMax - bind.fromMin
        if (span <= 0f) return null
        val raw = (value - bind.fromMin) / span
        return if (bind.clamp) raw.coerceIn(0f, 1f) else raw
    }

    fun output(bind: CustomBindSpec, fraction: Float): Float =
        bind.toMin + (bind.toMax - bind.toMin) * fraction

    fun color(bind: CustomBindSpec, fraction: Float): Int? {
        if (bind.colors.size < 2) return null
        return blend(bind.colors[0], bind.colors[1], fraction.coerceIn(0f, 1f))
    }

    /** Straight ARGB interpolation; accessible from pure JVM tests (no android.graphics). */
    fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
