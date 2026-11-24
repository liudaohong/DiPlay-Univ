package com.shilapi.xcertplay

import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression test for the `card112` keyframes.
 *
 * The 112 card used to be moved with `view.x = dst + dx` and then immediately reset with
 * `translationX = 0`, so its position keyframes never reached the panel even though the Theme
 * Maker preview animated them (field report 2026-11-11). The theme clock is advanced by hand here
 * so the assertion does not depend on the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CustomThemeHostAnimationTest {

    private fun card112Layer(): CustomLayerSpec = CustomLayerSpec(
        id = "card112",
        type = "live",
        z = 40f,
        rect = RectF(20f, 20f, 500f, 220f),
        dstSpecified = true,
        alpha = 1f,
        fit = "cover",
        src = null,
        slot = "card112",
        scale = 1f,
        paint = null,
        gauge = null,
        shape = "rect",
        text = "",
        textSize = 24f,
        textColor = 0,
        condition = null,
    )

    @Test
    fun `card112 x keyframe moves the view`() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val card = View(context)
        root.addView(card, FrameLayout.LayoutParams(480, 200))
        val spec = CustomThemeSpec(
            id = "test",
            name = "test",
            description = "",
            dir = File("."),
            layers = listOf(card112Layer()),
            animations = listOf(
                CustomAnimationSpec(
                    target = "card112",
                    property = "x",
                    durationMs = 3000L,
                    loop = true,
                    keyframes = listOf(0f to 0f, 0.5f to 80f, 1f to 0f),
                ),
            ),
        )
        val host = CustomThemeHost(
            context = context,
            root = root,
            spec = spec,
            liveViews = mapOf("card112" to card),
            appleFullMap = { false },
        )

        host.attach()
        host.tickOnce(0L)
        // dst x with no keyframe offset: the view is still at 20.
        assertEquals(20f, card.translationX, 0.5f)

        host.tickOnce(1500L)
        // Half way through the 3 s loop the keyframe asks for +80.
        assertEquals(100f, card.translationX, 0.5f)

        host.tickOnce(3000L)
        // Loop wraps back to the dst position.
        assertEquals(20f, card.translationX, 0.5f)

        // attach() now starts the shared vehicle poller; leave it balanced for the other tests.
        host.detach()
    }
}
