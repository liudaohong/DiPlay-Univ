package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerThemeSwipeTest {

    @Test
    fun parallelHorizontalSwipeSwitches() {
        assertTrue(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = -320f,
                dy = 24f,
                firstDx = -300f,
                secondDx = -340f,
                startSeparation = 220f,
                endSeparation = 220f,
                maxSeparationRatio = 0.02f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }

    @Test
    fun pinchDoesNotSwitch() {
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 24f,
                dy = 18f,
                firstDx = -120f,
                secondDx = 140f,
                startSeparation = 220f,
                endSeparation = 100f,
                maxSeparationRatio = 0.55f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }

    @Test
    fun aPinchLikeSeparationChangeDoesNotSwitch() {
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 300f,
                dy = 10f,
                firstDx = 280f,
                secondDx = 320f,
                startSeparation = 220f,
                endSeparation = 180f,
                maxSeparationRatio = 0.18f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }

    @Test
    fun verticalSwipeDoesNotSwitch() {
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 80f,
                dy = 300f,
                firstDx = 80f,
                secondDx = 80f,
                startSeparation = 220f,
                endSeparation = 220f,
                maxSeparationRatio = 0f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }

    @Test
    fun shortOrCloseStartDoesNotSwitch() {
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 180f,
                dy = 10f,
                firstDx = 180f,
                secondDx = 180f,
                startSeparation = 220f,
                endSeparation = 220f,
                maxSeparationRatio = 0f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 320f,
                dy = 10f,
                firstDx = 320f,
                secondDx = 320f,
                startSeparation = 60f,
                endSeparation = 60f,
                maxSeparationRatio = 0f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }

    @Test
    fun oneFingerStayingStillDoesNotSwitch() {
        assertFalse(
            TwoFingerThemeSwipe.shouldSwitch(
                dx = 300f,
                dy = 10f,
                firstDx = 40f,
                secondDx = 300f,
                startSeparation = 220f,
                endSeparation = 220f,
                maxSeparationRatio = 0f,
                minDistance = 192f,
                minSeparation = 108f,
            ),
        )
    }
}
