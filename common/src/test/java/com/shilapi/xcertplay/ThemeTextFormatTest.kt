package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTextFormatTest {

    private val data = ThemeData(
        speedKmh = 87.4f,
        rpm = 2350f,
        odometerKm = 43520.1f,
        rangeKm = 153f,
        night = true,
        appleFullMap = false,
        clockMinutes = 9 * 60 + 5,
        clockText = "09:05",
        navActive = true,
        navRemainingKm = "8.4 km",
        navRemainingMin = "12 分钟",
        navArrival = "21:40",
        navDelta = "+2 分",
        navSubtitle = "右转",
        songTitle = "歌名",
        songArtist = "歌手",
    )

    @Test
    fun `numeric placeholders use precision`() {
        assertEquals("87 km/h", ThemeTextFormat.format("{speed:0} km/h", data, "--"))
        assertEquals("87.4", ThemeTextFormat.format("{speed:1}", data, "--"))
        assertEquals("43520.1", ThemeTextFormat.format("{odo:1}", data, "--"))
        assertEquals("2350", ThemeTextFormat.format("{rpm}", data, "--"))
    }

    @Test
    fun `missing source falls back to the static text`() {
        val empty = ThemeData()
        assertEquals("--", ThemeTextFormat.format("{speed:0} km/h", empty, "--"))
        assertEquals("--", ThemeTextFormat.format("{unknown}", data, "--"))
        assertEquals("--", ThemeTextFormat.format("{songTitle}", empty, "--"))
    }

    @Test
    fun `clock and booleans expand`() {
        assertEquals("09:05 night=1 full=0", ThemeTextFormat.format(
            "{clock} night={night} full={appleFullMap}", data, "--",
        ))
    }

    @Test
    fun `navigation and song strings pass through`() {
        assertEquals(
            "8.4 km / 12 分钟 / 右转",
            ThemeTextFormat.format("{navRemainingKm} / {navRemainingMin} / {navSubtitle}", data, "--"),
        )
        assertEquals(
            "歌名 - 歌手",
            ThemeTextFormat.format("{songTitle} - {songArtist}", data, "--"),
        )
    }

    @Test
    fun `empty template returns the fallback`() {
        assertEquals("static", ThemeTextFormat.format("", data, "static"))
    }
}
