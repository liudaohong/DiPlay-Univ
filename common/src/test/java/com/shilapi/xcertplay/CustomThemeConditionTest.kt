package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeConditionTest {

    @Test
    fun `speed and rpm keep treating unknown as zero`() {
        // Old themes rely on the pre-ThemeData behaviour: NaN counted as 0.
        assertTrue(CustomCondition(maxSpeed = 0f).matches(ThemeData()))
        assertFalse(CustomCondition(minSpeed = 10f).matches(ThemeData()))
    }

    @Test
    fun `night and navigation flags`() {
        val night = ThemeData(night = true, navActive = true, songTitle = "歌")
        assertTrue(CustomCondition(night = true).matches(night))
        assertFalse(CustomCondition(night = false).matches(night))
        assertTrue(CustomCondition(navActive = true).matches(night))
        assertTrue(CustomCondition(songPlaying = true).matches(night))
        assertFalse(CustomCondition(songPlaying = false).matches(night))
        assertFalse(CustomCondition(navActive = true).matches(ThemeData()))
    }

    @Test
    fun `unknown range must not satisfy a threshold`() {
        // "unknown" is not 0: a low-range warning must not fire when the value is unavailable.
        assertFalse(CustomCondition(maxRange = 50f).matches(ThemeData()))
        assertTrue(CustomCondition(maxRange = 50f).matches(ThemeData(rangeKm = 40f)))
        assertTrue(CustomCondition(minRange = 100f).matches(ThemeData(rangeKm = 153f)))
        assertFalse(CustomCondition(minRange = 100f).matches(ThemeData(rangeKm = Float.NaN)))
    }

    @Test
    fun `gear turn and audio are visibleIf sources`() {
        val driving = ThemeData(gear = 4, turnLeft = true, audioLevel = 0.7f, audioBass = 0.5f)
        assertTrue(CustomCondition(minGear = 4f, maxGear = 4f).matches(driving))
        assertFalse(CustomCondition(maxGear = 3f).matches(driving))
        assertTrue(CustomCondition(turnLeft = true).matches(driving))
        assertFalse(CustomCondition(turnRight = true).matches(driving))
        assertTrue(CustomCondition(minAudioLevel = 0.5f).matches(driving))
        assertFalse(CustomCondition(maxAudioLevel = 0.5f).matches(driving))
        assertTrue(CustomCondition(minAudioBass = 0.4f).matches(driving))
        assertFalse(CustomCondition(minAudioBass = 0.6f).matches(driving))
    }

    @Test
    fun `clock range supports wrapping past midnight`() {
        val late = ThemeData(clockMinutes = 23 * 60 + 30)
        val noon = ThemeData(clockMinutes = 12 * 60)
        assertTrue(CustomCondition(clockFrom = 22 * 60, clockTo = 6 * 60).matches(late))
        assertFalse(CustomCondition(clockFrom = 22 * 60, clockTo = 6 * 60).matches(noon))
        assertTrue(CustomCondition(clockFrom = 8 * 60, clockTo = 18 * 60).matches(noon))
    }
}
