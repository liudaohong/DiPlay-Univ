package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PortholeThemeTest {

    @Test
    fun dayNightThemeHasSeparateNightPalette() {
        val theme = PortholeTheme.DAY_NIGHT
        assertEquals("daynight", theme.id)
        assertEquals(PortholeTheme.Decor.DAY_NIGHT, theme.decor)
        assertNotNull(theme.nightPalette)
        assertEquals(theme.palette, theme.paletteFor(night = false))
        assertEquals(theme.nightPalette, theme.paletteFor(night = true))
        assertNotEquals(theme.palette.cardBackground, theme.paletteFor(night = true).cardBackground)
        assertEquals(theme, PortholeTheme.fromId("daynight"))
        assertEquals(PortholeTheme.DEFAULT, PortholeTheme.fromId("missing"))
    }

    @Test
    fun mathThemeUsesItsOwnDecorAndPalette() {
        val theme = PortholeTheme.MATH
        assertEquals("math", theme.id)
        assertEquals(PortholeTheme.Decor.MATH, theme.decor)
        assertEquals(theme, PortholeTheme.fromId("math"))
        assertNotEquals(theme.palette.backdrop, PortholeTheme.RAY.palette.backdrop)
    }

    @Test
    fun dualRingThemeIsRegistered() {
        val theme = PortholeTheme.DUAL_RING
        assertEquals("dual_ring", theme.id)
        assertEquals(PortholeTheme.Decor.DUAL_RING, theme.decor)
        assertEquals(theme, PortholeTheme.fromId("dual_ring"))
    }

    @Test
    fun twoFingerThemeCycleWrapsBothWays() {
        val themes = PortholeTheme.entries
        assertEquals(themes[1], PortholeTheme.next(themes[0], 1))
        assertEquals(themes.last(), PortholeTheme.next(themes[0], -1))
        assertEquals(themes[0], PortholeTheme.next(themes.last(), 1))
        assertEquals(themes[0], PortholeTheme.next(themes[0], themes.size))
    }
}
