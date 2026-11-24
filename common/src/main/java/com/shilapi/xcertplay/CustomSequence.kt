package com.shilapi.xcertplay

/**
 * Phase-6 one-shot slide sequence. Pure JVM so the timing can be tested without a View.
 * Timeline: inMs (from -> to), holdMs (to), outMs (to -> from); after the last frame the
 * target stays at `from` and is optionally hidden.
 */
class CustomSequence(private val spec: CustomSequenceSpec) {

    data class Frame(val x: Float, val y: Float, val hidden: Boolean, val finished: Boolean)

    fun sample(elapsedMs: Long): Frame {
        val t = elapsedMs.coerceIn(0L, Long.MAX_VALUE)
        if (t < spec.delayMs) {
            return Frame(spec.fromX, spec.fromY, false, false)
        }
        val active = t - spec.delayMs
        val cycle = spec.inMs + spec.holdMs + spec.outMs
        if (active >= cycle * spec.repeat) {
            return Frame(spec.fromX, spec.fromY, spec.hideAfter, true)
        }
        val local = active % cycle.coerceAtLeast(1L)
        if (local < spec.inMs) {
            val f = smooth(local.toFloat() / spec.inMs.coerceAtLeast(1L))
            return Frame(lerp(spec.fromX, spec.toX, f), lerp(spec.fromY, spec.toY, f), false, false)
        }
        if (local < spec.inMs + spec.holdMs) {
            return Frame(spec.toX, spec.toY, false, false)
        }
        val outElapsed = local - spec.inMs - spec.holdMs
        val f = smooth(outElapsed.toFloat() / spec.outMs.coerceAtLeast(1L))
        return Frame(lerp(spec.toX, spec.fromX, f), lerp(spec.toY, spec.fromY, f), false, false)
    }

    private fun smooth(raw: Float): Float {
        val f = raw.coerceIn(0f, 1f)
        return f * f * (3f - 2f * f)
    }

    private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f
}
