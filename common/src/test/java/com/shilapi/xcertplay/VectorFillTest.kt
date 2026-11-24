package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorFillTest {

    @Test
    fun `fraction clamps and treats NaN as empty`() {
        assertEquals(0f, VectorFill.fraction(Float.NaN, 0f, 220f), 0.001f)
        assertEquals(0f, VectorFill.fraction(-5f, 0f, 220f), 0.001f)
        assertEquals(0.5f, VectorFill.fraction(110f, 0f, 220f), 0.001f)
        assertEquals(1f, VectorFill.fraction(999f, 0f, 220f), 0.001f)
    }

    @Test
    fun `band grows from the correct edge`() {
        assertArrayEquals(floatArrayOf(130f, 260f), VectorFill.band(260f, 0.5f, true), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 130f), VectorFill.band(260f, 0.5f, false), 0.001f)
    }

    @Test
    fun `radial radius and sweep degrees follow the fraction`() {
        assertEquals(7.5f, VectorFill.radius(6f, 4.5f, 1f), 0.001f)
        assertEquals(180f, VectorFill.sweepDegrees(0.5f), 0.001f)
        assertEquals(360f, VectorFill.sweepDegrees(4f), 0.001f)
    }
}
