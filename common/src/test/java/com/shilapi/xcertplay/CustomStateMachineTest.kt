package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomStateMachineTest {

    private fun data(speed: Float) = ThemeData(speedKmh = speed)

    private fun states() = listOf(
        CustomStateSpec(
            id = "parking",
            condition = CustomRuleCondition("speed", "lte", 5f, null),
            transitionMs = 0,
            overrides = mapOf(
                "hint" to CustomStateOverride(visible = true, color = 0xFFFF0000.toInt()),
                "gauge" to CustomStateOverride(visible = false),
            ),
        ),
        CustomStateSpec(
            id = "driving",
            condition = CustomRuleCondition("speed", "gt", 5f, null),
            transitionMs = 1000,
            overrides = mapOf(
                "hint" to CustomStateOverride(visible = false, alpha = 0.2f, scale = 0.8f, x = 10f,
                    color = 0xFF00FF00.toInt()),
                "gauge" to CustomStateOverride(visible = true),
            ),
        ),
        CustomStateSpec(
            id = "base",
            condition = null,
            transitionMs = 300,
            overrides = mapOf("base_badge" to CustomStateOverride(visible = true)),
        ),
    )

    @Test
    fun `switches states and interpolates overrides`() {
        val machine = CustomStateMachine(states())
        val parking = machine.evaluate(data(0f), 0)
        assertEquals("parking", parking.activeId)
        assertEquals(true, parking.visible["hint"])
        assertEquals(false, parking.visible["gauge"])

        machine.evaluate(data(30f), 100)
        val mid = machine.evaluate(data(30f), 600)
        assertEquals("driving", mid.activeId)
        assertEquals(true, mid.visible["hint"]) // stays visible until the fade ends
        assertEquals(0.6f, mid.alpha["hint"]!!, 0.001f)
        assertEquals(0.9f, mid.scale["hint"]!!, 0.001f)
        assertEquals(5f, mid.x["hint"]!!, 0.001f)
        assertEquals(0xFF7F7F00.toInt(), mid.color["hint"]!!)

        val done = machine.evaluate(data(30f), 1101)
        assertEquals(false, done.visible["hint"])
        assertEquals(0.2f, done.alpha["hint"]!!, 0.001f)
        assertEquals(true, done.visible["gauge"])
    }

    @Test
    fun `falls back to the base state without when`() {
        val machine = CustomStateMachine(states())
        // No condition matches speed NaN, so the last state without `when` is used.
        val frame = machine.evaluate(ThemeData(), 0)
        assertEquals("base", frame.activeId)
        assertEquals(true, frame.visible["base_badge"])
    }

    @Test
    fun `intro is held then transitions to the matching state`() {
        val states = listOf(
            CustomStateSpec("intro", CustomRuleCondition("odo", "lt", 0f, null), 0,
                mapOf("title" to CustomStateOverride(alpha = 0f))),
            CustomStateSpec("parking", CustomRuleCondition("speed", "lte", 5f, null), 400,
                mapOf("title" to CustomStateOverride(alpha = 1f))),
        )
        val machine = CustomStateMachine(states)
        machine.playIntro("intro", 500L, 0L)
        assertEquals("intro", machine.evaluate(data(0f), 100).activeId)
        assertEquals(0f, machine.evaluate(data(0f), 100).alpha["title"]!!, 0.001f)
        assertEquals("parking", machine.evaluate(data(0f), 501).activeId)
        val mid = machine.evaluate(data(0f), 701)
        assertEquals(0.5f, mid.alpha["title"]!!, 0.01f)
        val done = machine.evaluate(data(0f), 902)
        assertEquals(1f, done.alpha["title"]!!, 0.001f)
    }

    @Test
    fun `dst interpolates between state rects and the base rect`() {
        val states = listOf(
            CustomStateSpec("parking", CustomRuleCondition("speed", "lte", 5f, null), 0,
                mapOf("hint" to CustomStateOverride(dst = StateRect(0f, 0f, 100f, 100f)))),
            CustomStateSpec("driving", CustomRuleCondition("speed", "gt", 5f, null), 1000,
                mapOf("hint" to CustomStateOverride(dst = StateRect(200f, 0f, 200f, 100f)))),
        )
        val machine = CustomStateMachine(states, mapOf("hint" to StateRect(0f, 0f, 100f, 100f)))
        val parking = machine.evaluate(data(0f), 0)
        assertEquals(0f, parking.dst["hint"]!!.x, 0.001f)
        machine.evaluate(data(30f), 100)
        val mid = machine.evaluate(data(30f), 600)
        assertEquals(100f, mid.dst["hint"]!!.x, 0.001f)
        assertEquals(150f, mid.dst["hint"]!!.w, 0.001f)
        val done = machine.evaluate(data(30f), 1101)
        assertEquals(200f, done.dst["hint"]!!.x, 0.001f)
    }

    @Test
    fun `navigation state uses a 300ms transition`() {
        val states = listOf(
            CustomStateSpec("nav", CustomRuleCondition("navActive", "eq", 1f, null), 300,
                mapOf("title" to CustomStateOverride(alpha = 0.5f))),
            CustomStateSpec("parking", CustomRuleCondition("speed", "lte", 5f, null), 0,
                mapOf("title" to CustomStateOverride(alpha = 1f))),
        )
        val machine = CustomStateMachine(states)
        machine.evaluate(ThemeData(speedKmh = 0f), 0)
        machine.evaluate(ThemeData(speedKmh = 0f, navActive = true), 100)
        val mid = machine.evaluate(ThemeData(speedKmh = 0f, navActive = true), 250)
        assertEquals("nav", mid.activeId)
        assertEquals(0.75f, mid.alpha["title"]!!, 0.01f)
        val done = machine.evaluate(ThemeData(speedKmh = 0f, navActive = true), 401)
        assertEquals(0.5f, done.alpha["title"]!!, 0.001f)
    }

    @Test
    fun `reset forgets the active state`() {
        val machine = CustomStateMachine(states())
        machine.evaluate(data(30f), 0)
        machine.reset()
        val frame = machine.evaluate(data(30f), 0)
        assertEquals("driving", frame.activeId)
        assertEquals(false, frame.visible["hint"])
    }
}
