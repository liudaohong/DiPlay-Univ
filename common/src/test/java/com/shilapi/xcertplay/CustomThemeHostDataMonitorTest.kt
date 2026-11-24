package com.shilapi.xcertplay

import android.content.Context
import android.graphics.RectF
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * The custom theme host owns vehicle-data polling (field report 2026-11-12: the built-in theme's
 * arcs followed speed/rpm, an imported custom theme's gauges stayed at 0 because nothing called
 * `CarDataMonitor.start()`). These tests inject counters instead of touching the global poller,
 * so they are deterministic and cannot leak into other test classes that share the sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CustomThemeHostDataMonitorTest {

    private var starts = 0
    private var stops = 0

    private fun textLayer(): CustomLayerSpec = CustomLayerSpec(
        id = "label",
        type = "text",
        z = 1f,
        rect = RectF(10f, 10f, 210f, 60f),
        dstSpecified = true,
        alpha = 1f,
        fit = "stretch",
        src = null,
        slot = "",
        scale = 1f,
        paint = null,
        gauge = null,
        shape = "rect",
        text = "TEST",
        textSize = 24f,
        textColor = 0xFFFFFFFF.toInt(),
        condition = null,
    )

    private fun host(context: Context, root: FrameLayout): CustomThemeHost = CustomThemeHost(
        context = context,
        root = root,
        spec = CustomThemeSpec(
            id = "test",
            name = "test",
            description = "",
            dir = File("."),
            layers = listOf(textLayer()),
            animations = emptyList(),
        ),
        liveViews = emptyMap(),
        appleFullMap = { false },
        startVehicleData = { starts++ },
        stopVehicleData = { stops++ },
    )

    @Test
    fun `attach starts data and detach stops it`() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val host = host(context, root)

        host.attach()
        assertEquals(1, starts)

        host.detach()
        assertEquals(1, stops)
    }

    @Test
    fun `repeated attach and detach stay balanced`() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val host = host(context, root)

        host.attach()
        host.attach()
        assertEquals(1, starts)

        host.detach()
        host.detach()
        assertEquals(1, stops)
    }

    /**
     * A dismissed Presentation detaches the root view without calling detach(); the poller must
     * stop then, and resume if the same window is attached again.
     */
    @Test
    fun `root window detach stops data and reattach restarts it`() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val host = host(context, root)

        host.attach()
        assertEquals(1, starts)

        val shadow = Shadows.shadowOf(root)
        assertEquals(1, shadow.onAttachStateChangeListeners.size)
        val listener = shadow.onAttachStateChangeListeners.first()

        listener.onViewDetachedFromWindow(root)
        assertEquals(1, stops)

        listener.onViewAttachedToWindow(root)
        assertEquals(2, starts)

        host.detach()
        assertEquals(2, stops)
        assertEquals(0, shadow.onAttachStateChangeListeners.size)
    }
}
