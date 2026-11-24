package com.shilapi.xcertplay

import kotlin.math.hypot

/** Pure math for vector fill; kept Android-free so it can be unit tested on the JVM. */
internal object VectorFill {

    /** NaN/absent data fills 0, per the vector spec. */
    fun fraction(value: Float, min: Float, max: Float): Float {
        if (!value.isFinite()) return 0f
        val span = max - min
        if (span <= 0f) return 0f
        return ((value - min) / span).coerceIn(0f, 1f)
    }

    /** [start,end] band of [length]; [fromEnd] = true means the last `length*fraction`. */
    fun band(length: Float, fraction: Float, fromEnd: Boolean): FloatArray {
        val size = (length * fraction.coerceIn(0f, 1f)).coerceIn(0f, length)
        return if (fromEnd) floatArrayOf(length - size, length) else floatArrayOf(0f, size)
    }

    fun radius(halfWidth: Float, halfHeight: Float, fraction: Float): Float =
        hypot(halfWidth, halfHeight) * fraction.coerceIn(0f, 1f)

    fun sweepDegrees(fraction: Float): Float = 360f * fraction.coerceIn(0f, 1f)
}
