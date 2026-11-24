package com.shilapi.xcertplay

import kotlin.math.abs

/**
 * Decision for the two-finger porthole-theme swipe on the CarPlay main screen.
 *
 * The gesture is deliberately non-consuming: CarPlay and Amap still receive every event.
 * These tests therefore only decide whether the fingers were moving together horizontally
 * (a theme flip) rather than pinching (Amap's zoom, leave it alone).
 */
internal object TwoFingerThemeSwipe {
    const val MAX_SEPARATION_RATIO = 0.15f
    const val HORIZONTAL_ASPECT = 2f
    const val MIN_PARALLEL_SHARE = 0.45f

    fun shouldSwitch(
        dx: Float,
        dy: Float,
        firstDx: Float,
        secondDx: Float,
        startSeparation: Float,
        endSeparation: Float,
        maxSeparationRatio: Float,
        minDistance: Float,
        minSeparation: Float,
    ): Boolean {
        if (startSeparation < minSeparation || startSeparation < 1f) return false
        if (abs(dx) < minDistance) return false
        if (abs(dx) < HORIZONTAL_ASPECT * abs(dy)) return false
        // A pinch changes the distance between the fingers; a parallel two-finger pan does not.
        if (maxSeparationRatio > MAX_SEPARATION_RATIO) return false
        if (abs(endSeparation - startSeparation) / startSeparation > MAX_SEPARATION_RATIO) return false
        // Both fingers must travel the same horizontal way. In a pinch they travel oppositely.
        if (firstDx * secondDx <= 0f) return false
        val slower = minOf(abs(firstDx), abs(secondDx))
        return slower >= minDistance * MIN_PARALLEL_SHARE
    }
}
