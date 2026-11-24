package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTouchSlotsTest {
    @Test
    fun keepsFingerPinnedToOriginalSlotAndReleasesAtLastPosition() {
        val slots = AirPlayTouchSlots(2)

        val first = slots.assign(listOf(AirPlayContact(id = 7, x = 0.2, y = 0.3, down = true)))
        assertEquals(listOf(AirPlayContact(id = 0, x = 0.2, y = 0.3, down = true)), first)

        val both = slots.assign(
            listOf(
                AirPlayContact(id = 7, x = 0.25, y = 0.35, down = true),
                AirPlayContact(id = 11, x = 0.8, y = 0.7, down = true),
            ),
        )
        assertEquals(0, both[0].id)
        assertEquals(1, both[1].id)

        // Finger 7 lifts while finger 11 stays down: finger 11 must remain in slot 1,
        // and slot 0 must be released at (0.25, 0.35), NOT (0, 0).
        val afterFirstLift = slots.assign(listOf(AirPlayContact(id = 11, x = 0.82, y = 0.72, down = true)))
        assertEquals(2, afterFirstLift.size)
        assertEquals(0, afterFirstLift[0].id)
        assertFalse(afterFirstLift[0].down)
        assertEquals(0.25, afterFirstLift[0].x, 1e-6)
        assertEquals(0.35, afterFirstLift[0].y, 1e-6)
        assertEquals(1, afterFirstLift[1].id)
        assertTrue(afterFirstLift[1].down)

        // Empty list releases the remaining held finger at its last coordinate.
        val releaseAll = slots.assign(emptyList())
        assertEquals(1, releaseAll.size)
        assertEquals(1, releaseAll[0].id)
        assertFalse(releaseAll[0].down)
        assertEquals(0.82, releaseAll[0].x, 1e-6)
    }
}
