package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeBindTest {

    private fun bind(
        fromMin: Float = 0f,
        fromMax: Float = 220f,
        toMin: Float = 0.4f,
        toMax: Float = 1.0f,
        clamp: Boolean = true,
        property: String = "alpha",
    ) = CustomBindSpec(
        source = "speed",
        property = property,
        fromMin = fromMin,
        fromMax = fromMax,
        toMin = toMin,
        toMax = toMax,
        clamp = clamp,
        easeMs = 120L,
        colors = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
    )

    @Test
    fun `fraction maps the input range and clamps by default`() {
        val b = bind()
        assertEquals(0.5f, ThemeBind.fraction(b, 110f)!!, 0.001f)
        assertEquals(1f, ThemeBind.fraction(b, 300f)!!, 0.001f)
        assertEquals(0f, ThemeBind.fraction(b, -20f)!!, 0.001f)
    }

    @Test
    fun `clamp false extrapolates for offsets`() {
        val b = bind(clamp = false, toMin = -40f, toMax = 40f)
        assertEquals(1.5f, ThemeBind.fraction(b, 330f)!!, 0.001f)
        assertEquals(80f, ThemeBind.output(b, ThemeBind.fraction(b, 330f)!!), 0.001f)
    }

    @Test
    fun `output interpolates the target range`() {
        val b = bind()
        assertEquals(0.4f, ThemeBind.output(b, 0f), 0.001f)
        assertEquals(0.7f, ThemeBind.output(b, 0.5f), 0.001f)
        assertEquals(1f, ThemeBind.output(b, 1f), 0.001f)
    }

    @Test
    fun `color blends argb endpoints and midpoint`() {
        val b = bind(property = "color")
        assertEquals(0xFFFF0000.toInt(), ThemeBind.color(b, 0f))
        assertEquals(0xFF0000FF.toInt(), ThemeBind.color(b, 1f))
        assertEquals(0xFF7F007F.toInt(), ThemeBind.color(b, 0.5f))
    }

    @Test
    fun `unknown value has no fraction`() {
        assertNull(ThemeBind.fraction(bind(), Float.NaN))
    }
}
