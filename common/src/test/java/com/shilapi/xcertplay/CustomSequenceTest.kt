package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSequenceTest {

    private fun spec(hideAfter: Boolean = true) = CustomSequenceSpec(
        targets = setOf("title", "hint"), delayMs = 100, inMs = 300, holdMs = 800, outMs = 300,
        fromX = -600f, fromY = 0f, toX = 0f, toY = 0f, hideAfter = hideAfter,
    )

    @Test
    fun `slides in holds then slides out and hides`() {
        val seq = CustomSequence(spec())
        assertEquals(-600f, seq.sample(0).x, 0.001f)
        assertEquals(-600f, seq.sample(100).x, 0.001f)
        assertEquals(-300f, seq.sample(250).x, 0.001f) // smoothstep midpoint
        assertEquals(0f, seq.sample(400).x, 0.001f)
        assertEquals(0f, seq.sample(1000).x, 0.001f)
        assertEquals(0f, seq.sample(1200).x, 0.001f)
        assertEquals(-300f, seq.sample(1350).x, 0.001f)
        val done = seq.sample(1500)
        assertTrue(done.finished)
        assertTrue(done.hidden)
        assertFalse(seq.sample(1499).finished)
    }

    @Test
    fun `repeat plays the cycle again before hiding`() {
        val spec = spec().copy(repeat = 2)
        val seq = CustomSequence(spec)
        assertFalse(seq.sample(1500).finished)
        val second = seq.sample(1550)
        assertFalse(second.hidden)
        assertTrue(second.x > -600f && second.x < 0f)
        val done = seq.sample(3000)
        assertTrue(done.finished)
        assertTrue(done.hidden)
    }

    @Test
    fun `hideAfter false keeps the layer visible at the from offset`() {
        val done = CustomSequence(spec(hideAfter = false)).sample(5000);
        assertTrue(done.finished)
        assertFalse(done.hidden)
        assertEquals(-600f, done.x, 0.001f)
    }
}
