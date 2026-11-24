package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuleEngineTest {

    private fun hideRule(
        forMs: Long = 3000,
        cooldownMs: Long = 30000,
        release: Float? = 110f,
    ) = CustomRuleSpec(
        id = "overspeed",
        condition = CustomRuleCondition("speed", "gt", 120f, release),
        forMs = forMs,
        cooldownMs = cooldownMs,
        actions = listOf(
            CustomRuleAction("hide", "warn", 500),
            CustomRuleAction("flash", "warn", 400),
        ),
    )

    private fun data(speed: Float = Float.NaN, nav: Boolean = false, night: Boolean = false) =
        ThemeData(speedKmh = speed, navActive = nav, night = night)

    @Test
    fun `fires only after forMs and stays active while true`() {
        val engine = CustomRuleEngine(listOf(hideRule()))
        assertFalse(engine.evaluate(data(130f), 0).hidden.contains("warn"))
        assertFalse(engine.evaluate(data(130f), 2_999).hidden.contains("warn"))
        assertTrue(engine.evaluate(data(130f), 3_000).hidden.contains("warn"))
    }

    @Test
    fun `hysteresis keeps the rule active between release and threshold`() {
        val engine = CustomRuleEngine(listOf(hideRule()))
        engine.evaluate(data(130f), 0)
        assertTrue(engine.evaluate(data(130f), 3_000).hidden.contains("warn"))
        assertTrue(engine.evaluate(data(115f), 4_000).hidden.contains("warn"))
        assertFalse(engine.evaluate(data(109f), 5_000).hidden.contains("warn"))
    }

    @Test
    fun `cooldown blocks a second rising edge`() {
        val engine = CustomRuleEngine(listOf(hideRule()))
        engine.evaluate(data(130f), 0)
        assertTrue(engine.evaluate(data(130f), 3_000).hidden.contains("warn"))
        engine.evaluate(data(100f), 4_000)
        engine.evaluate(data(130f), 5_000)
        assertFalse(engine.evaluate(data(130f), 8_000).hidden.contains("warn"))
        assertTrue(engine.evaluate(data(130f), 33_000).hidden.contains("warn"))
    }

    @Test
    fun `flash alternates from the fire time`() {
        val engine = CustomRuleEngine(listOf(hideRule()))
        engine.evaluate(data(130f), 0)
        val atFire = engine.evaluate(data(130f), 3_000)
        assertEquals(1f, atFire.flashAlpha["warn"]!!, 0.001f)
        assertEquals(0.25f, engine.evaluate(data(130f), 3_400).flashAlpha["warn"]!!, 0.001f)
        assertEquals(1f, engine.evaluate(data(130f), 3_800).flashAlpha["warn"]!!, 0.001f)
    }

    @Test
    fun `boolean source works`() {
        val rule = CustomRuleSpec(
            id = "nav",
            condition = CustomRuleCondition("navActive", "eq", 1f, null),
            forMs = 0,
            cooldownMs = 0,
            actions = listOf(CustomRuleAction("show", "nav_hint", 500)),
        )
        val engine = CustomRuleEngine(listOf(rule))
        assertTrue(engine.evaluate(data(nav = false), 0).shown.isEmpty())
        assertTrue(engine.evaluate(data(nav = true), 100).shown.contains("nav_hint"))
    }

    @Test
    fun `speedDelta detects one second plus twenty`() {
        val rule = CustomRuleSpec(
            id = "accel",
            condition = CustomRuleCondition("speedDelta", "gt", 20f, 15f, 1000L),
            forMs = 0,
            cooldownMs = 0,
            actions = listOf(CustomRuleAction("hide", "accel_warn", 500)),
        )
        val engine = CustomRuleEngine(listOf(rule))
        assertFalse(engine.evaluate(data(100f), 0).hidden.contains("accel_warn"))
        assertTrue(engine.evaluate(data(121f), 1_000).hidden.contains("accel_warn"))
    }

    @Test
    fun `speedDelta needs enough history`() {
        val rule = CustomRuleSpec(
            id = "accel",
            condition = CustomRuleCondition("speedDelta", "gt", 20f, null, 1000L),
            forMs = 0,
            cooldownMs = 0,
            actions = listOf(CustomRuleAction("hide", "accel_warn", 500)),
        )
        val engine = CustomRuleEngine(listOf(rule))
        assertFalse(engine.evaluate(data(150f), 1_000).hidden.contains("accel_warn"))
    }

    @Test
    fun `pulse and setText actions apply and honor TTL`() {
        val rule = CustomRuleSpec(
            id = "boost",
            condition = CustomRuleCondition("speed", "gt", 0f, null),
            forMs = 0,
            cooldownMs = 0,
            actions = listOf(
                CustomRuleAction("pulse", "badge", 400, amplitude = 0.2f, durationMs = 1_000),
                CustomRuleAction("setText", "label", 500, text = "BOOST"),
                CustomRuleAction("setImage", "badge", 500, imageSrc = "alert.png"),
            ),
        )
        val engine = CustomRuleEngine(listOf(rule))
        val start = engine.evaluate(data(50f), 0)
        assertEquals(1f, start.pulseScale["badge"]!!, 0.001f)
        assertEquals("BOOST", start.text["label"])
        assertEquals("alert.png", start.images["badge"])
        assertEquals(1.2f, engine.evaluate(data(50f), 100).pulseScale["badge"]!!, 0.001f)
        val expired = engine.evaluate(data(50f), 1_500)
        assertFalse(expired.pulseScale.containsKey("badge"))
        assertEquals("BOOST", expired.text["label"])
    }

    @Test
    fun `rule source and action names are canonicalized case-insensitively`() {
        assertEquals("speedDelta", CustomThemeRepository.canonicalRuleSource("speedDelta"))
        assertEquals("speedDelta", CustomThemeRepository.canonicalRuleSource("speeddelta"))
        assertEquals("appleFullMap", CustomThemeRepository.canonicalRuleSource("APPLEFULLMAP"))
        assertEquals("setText", CustomThemeRepository.canonicalRuleAction("settext"))
        assertEquals(null, CustomThemeRepository.canonicalRuleSource("bogus"))
        assertEquals(null, CustomThemeRepository.canonicalRuleAction("bogus"))
    }

    @Test
    fun `all any and not combinators`() {
        val fast = CustomRuleCondition("speed", "gte", 50f, null)
        val night = CustomRuleCondition("night", "eq", 1f, null)
        val all = CustomRuleSpec(
            id = "all",
            condition = fast,
            forMs = 0,
            cooldownMs = 0,
            actions = listOf(CustomRuleAction("hide", "all_warn", 500)),
            conditions = listOf(fast, night),
            combine = "all",
        )
        val any = all.copy(
            id = "any",
            conditions = listOf(fast, night),
            combine = "any",
            actions = listOf(CustomRuleAction("hide", "any_warn", 500)),
        )
        val not = all.copy(
            id = "not",
            condition = CustomRuleCondition("speed", "gt", 120f, null),
            conditions = emptyList(),
            combine = "single",
            negate = true,
            actions = listOf(CustomRuleAction("hide", "not_warn", 500)),
        )
        val engine = CustomRuleEngine(listOf(all, any, not))
        val frame = engine.evaluate(data(60f, night = true), 0)
        assertTrue(frame.hidden.contains("all_warn"))
        assertTrue(frame.hidden.contains("any_warn"))
        assertTrue(frame.hidden.contains("not_warn"))
        val noNight = engine.evaluate(data(60f, night = false), 100)
        assertFalse(noNight.hidden.contains("all_warn"))
        assertTrue(noNight.hidden.contains("any_warn"))
        assertTrue(noNight.hidden.contains("not_warn"))
    }

    @Test
    fun `reset forgets the hold timer and fired state`() {
        val engine = CustomRuleEngine(listOf(hideRule(forMs = 1000)))
        engine.evaluate(data(130f), 0)
        engine.reset()
        assertFalse(engine.evaluate(data(130f), 1_000).hidden.contains("warn"))
        assertTrue(engine.evaluate(data(130f), 2_000).hidden.contains("warn"))
    }
}
