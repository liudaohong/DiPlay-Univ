package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shilapi.xcertplay.daynight.CarDataMonitor

/** A live view that can place its card at a custom panel position. */
internal interface CustomPositionable {
    fun setCustomPosition(x: Float, y: Float)
    fun clearCustomPosition()
}

/**
 * Live data cards (ETA/song) whose drawing surface covers the whole panel. Their native design
 * box is 430x165; a custom theme's dst width/height scales that box through the View scale.
 * The nav map is handled separately because it is a real TextureView viewport, and card112 is a
 * fixed-aspect video stream that ignores width/height.
 */
internal interface CustomResizableLiveView

/**
 * Runtime View host for a custom theme.
 *
 * Every declarative layer becomes one full-screen transparent sibling View. The existing live
 * porthole Views (nav window, 112 card, ETA, song) stay where they are and only get z-order,
 * visibility, position/scale changes. Because all of them are siblings of the root FrameLayout,
 * a custom layer can be placed above or below each live slot independently.
 */
internal class CustomThemeHost(
    private val context: Context,
    private val root: ViewGroup,
    private val spec: CustomThemeSpec,
    private val liveViews: Map<String, View?>,
    private val appleFullMap: () -> Boolean,
    /**
     * One sampled frame per tick, shared by text bindings, gauges, swap rules and visibleIf.
     * Defaults to a snapshot carrying only [appleFullMap] so older call sites/tests keep working.
     */
    private val data: () -> ThemeData = { ThemeData(appleFullMap = appleFullMap()) },
    /**
     * The porthole map is not a fixed-size card: its nav dst width/height define the real
     * viewport. Other live slots (112/ETA/song) keep their native size and use [CustomLayerSpec.scale].
     */
    private val onNavRect: ((x: Float, y: Float, w: Float, h: Float, scale: Float) -> Unit)? = null,
    /** Called after theme views are re-stacked so the PC MCU can be put back on top. */
    private val onLayerOrderChanged: (() -> Unit)? = null,
    /**
     * Vehicle-data ownership. The theme host is the only component that knows when a custom
     * theme is on screen, so it must start the poller itself; before this the gauge/visibleIf/
     * swap layers read NaN on the car unless a built-in theme or the bench MCU happened to be
     * running (field report 2026-11-12: the built-in arcs moved, imported themes stayed at 0).
     * Injected so unit tests can observe start/stop without touching the global singleton.
     */
    private val startVehicleData: (Context) -> Unit = { CarDataMonitor.start(it) },
    private val stopVehicleData: () -> Unit = { CarDataMonitor.stop() },
) {
    private val customViews = ArrayList<CustomThemeLayerView>()
    private val liveLayers = spec.layers.filter { it.type == "live" }
    private var running = false
    private var startMs = 0L
    private var lastZLogAt = 0L
    private var dataMonitorStarted = false
    private val ruleEngine = CustomRuleEngine(spec.rules)
    private val stateMachine = CustomStateMachine(
        spec.states,
        spec.layers.associate { it.id to StateRect(it.rect.left, it.rect.top, it.rect.width(), it.rect.height()) },
    )
    private val sequence = spec.sequence?.let { CustomSequence(it) }
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tickOnce(SystemClock.elapsedRealtime() - startMs)
            root.postDelayed(this, 33L)
        }
    }

    /**
     * A Presentation can be dismissed without detach() being called; the root view then leaves
     * the window while the 150 ms poller would keep running. Stop it on detach and restore it if
     * the same window is re-attached.
     */
    private val rootAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (running) startDataMonitor()
        }

        override fun onViewDetachedFromWindow(v: View) {
            stopDataMonitor()
        }
    }

    private fun startDataMonitor() {
        if (dataMonitorStarted) return
        dataMonitorStarted = true
        startVehicleData(context)
    }

    private fun stopDataMonitor() {
        if (!dataMonitorStarted) return
        dataMonitorStarted = false
        stopVehicleData()
    }

    private fun themeVisible(
        layer: CustomLayerSpec?,
        frame: ThemeData,
        rules: CustomRuleEngine.Frame,
    ): Boolean {
        val condition = layer?.condition?.matches(frame) ?: true
        val id = layer?.id ?: return condition
        val shown = rules.shown.contains(id)
        return (condition || shown) && !rules.hidden.contains(id)
    }

    /**
     * One frame of the theme clock. Separate from the runnable so a unit test can advance the
     * animation deterministically (the ticker itself is driven by the main looper).
     */
    internal fun tickOnce(now: Long) {
        val frame = data()
        val rules = ruleEngine.evaluate(frame, now)
        val states = stateMachine.evaluate(frame, now)
        val sequenceFrame = sequence?.sample(now)
        for (view in customViews) {
            val layer = view.tag as? CustomLayerSpec
            view.setData(frame)
            val id = layer?.id
            val ruleAlpha = if (layer != null) rules.flashAlpha[layer.id] ?: 1f else 1f
            val stateAlpha = if (id != null) states.alpha[id] ?: 1f else 1f
            val ruleScale = if (layer != null) rules.pulseScale[layer.id] ?: 1f else 1f
            val stateScale = if (id != null) states.scale[id] ?: 1f else 1f
            view.setRuleAlpha(ruleAlpha * stateAlpha)
            view.setRuleScale(ruleScale * stateScale)
            view.setRuleText(if (layer != null) rules.text[layer.id] else null)
            view.setRuleImage(if (layer != null) rules.images[layer.id] else null)
            view.setStateOffset(if (id != null) states.x[id] ?: 0f else 0f,
                if (id != null) states.y[id] ?: 0f else 0f)
            view.setStateColor(if (id != null) states.color[id] else null)
            view.setStateDst(if (id != null) states.dst[id] else null)
            val seqTarget = sequenceFrame != null && id != null &&
                id in (spec.sequence?.targets ?: emptySet())
            view.setSequenceOffset(if (seqTarget) sequenceFrame!!.x else 0f,
                if (seqTarget) sequenceFrame!!.y else 0f)
            var visible = themeVisible(layer, frame, rules)
            if (id != null) states.visible[id]?.let { visible = it }
            if (seqTarget && sequenceFrame!!.hidden) visible = false
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.setClock(now)
        }
        for (layer in liveLayers) {
            val view = liveViews[layer.slot] ?: continue
            // applyCardWindowVisibility() keeps the built-in 112 card at Z=100 so it wins
            // over ordinary drawn cards. In a custom theme the layer z order is the truth:
            // put it back every tick, otherwise the map/112 card can cover a song layer
            // whose z is numerically lower/higher than the forced built-in value.
            view.translationZ = layer.z.coerceIn(0f, 1900f)
            var visible = themeVisible(layer, frame, rules)
            states.visible[layer.id]?.let { visible = it }
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.alpha = (rules.flashAlpha[layer.id] ?: 1f) * (states.alpha[layer.id] ?: 1f)
            if (view.visibility == View.VISIBLE) applyLiveAnimation(view, layer, now)
        }
        if (now - lastZLogAt > 1500L) {
            lastZLogAt = now
            val parts = liveLayers.mapNotNull { layer ->
                val view = liveViews[layer.slot] ?: return@mapNotNull null
                "${layer.slot}:layerZ=${layer.z}/viewZ=${view.translationZ}/idx=${root.indexOfChild(view)}"
            }
            android.util.Log.i("DiPlay-CustomTheme", "live z ${parts.joinToString(" ")}")
            val children = ArrayList<String>()
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) ?: continue
                children += "$i:${child.javaClass.simpleName}" +
                    "@${child.left},${child.top} ${child.width}x${child.height}" +
                    "/z=${child.translationZ}/v=${child.visibility}"
            }
            android.util.Log.i("DiPlay-CustomTheme", "root children ${children.joinToString(" | ")}")
        }
        // Some presentation code (the built-in 112 card) calls bringToFront outside the
        // theme host. translationZ alone is not reliable on every ROM/hardware window, so
        // also restore the real child order when it no longer matches the theme z order.
        reorderThemeLayers()
    }

    fun attach() {
        if (running) return
        ruleEngine.reset()
        stateMachine.reset()
        spec.intro?.let { stateMachine.playIntro(it.stateId, it.holdMs, 0L) }
        startMs = SystemClock.elapsedRealtime()
        val frame = data()
        val rules = ruleEngine.evaluate(frame, 0)
        val states = stateMachine.evaluate(frame, 0L)
        liveViews.values.forEach { it?.visibility = View.GONE }
        for (layer in spec.layers) {
            if (layer.type == "live") {
                val view = liveViews[layer.slot] ?: continue
                applyLive(view, layer)
                var visible = themeVisible(layer, frame, rules)
                states.visible[layer.id]?.let { visible = it }
                view.visibility = if (visible) View.VISIBLE else View.GONE
                view.alpha = (rules.flashAlpha[layer.id] ?: 1f) * (states.alpha[layer.id] ?: 1f)
            } else {
                val view = CustomThemeLayerView(context, layer, spec.animations)
                view.tag = layer
                view.translationZ = layer.z.coerceIn(0f, 1900f)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.setData(frame)
                view.setRuleAlpha((rules.flashAlpha[layer.id] ?: 1f) * (states.alpha[layer.id] ?: 1f))
                view.setRuleScale((rules.pulseScale[layer.id] ?: 1f) * (states.scale[layer.id] ?: 1f))
                view.setRuleText(rules.text[layer.id])
                view.setRuleImage(rules.images[layer.id])
                view.setStateOffset(states.x[layer.id] ?: 0f, states.y[layer.id] ?: 0f)
                view.setStateColor(states.color[layer.id])
                view.setStateDst(states.dst[layer.id])
                val seq = spec.sequence?.let { sequence?.sample(0) }
                val seqTarget = seq != null && spec.sequence != null && layer.id in spec.sequence.targets
                view.setSequenceOffset(if (seqTarget) seq!!.x else 0f, if (seqTarget) seq!!.y else 0f)
                var visible = themeVisible(layer, frame, rules)
                states.visible[layer.id]?.let { visible = it }
                if (seqTarget && seq!!.hidden) visible = false
                view.visibility = if (visible) View.VISIBLE else View.GONE
                customViews += view
            }
        }
        running = true
        startDataMonitor()
        root.addOnAttachStateChangeListener(rootAttachListener)
        reorderThemeLayers()
        ticker.run()
    }

    fun detach() {
        running = false
        ruleEngine.reset()
        stateMachine.reset()
        root.removeCallbacks(ticker)
        root.removeOnAttachStateChangeListener(rootAttachListener)
        stopDataMonitor()
        for (view in customViews) root.removeView(view)
        customViews.clear()
        for ((slot, view) in liveViews) {
            if (view == null) continue
            view.translationZ = 0f
            view.visibility = View.GONE
            if (view is CustomPositionable) view.clearCustomPosition()
            view.x = 0f
            view.y = 0f
            view.translationX = 0f
            view.translationY = 0f
            view.rotation = 0f
            view.alpha = 1f
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            if (slot == "card112") {
                view.visibility = View.GONE
                view.translationZ = 100f
            }
        }
    }

    private fun reorderThemeLayers() {
        val ordered = ArrayList<Pair<View, Float>>()
        for (view in customViews) {
            val layer = view.tag as? CustomLayerSpec ?: continue
            ordered += view to layer.z
        }
        for (layer in liveLayers) {
            val view = liveViews[layer.slot] ?: continue
            ordered += view to layer.z
        }
        if (ordered.size < 2) return
        ordered.sortBy { it.second }
        var previousIndex = -1
        var alreadyOrdered = true
        for ((view, _) in ordered) {
            val index = root.indexOfChild(view)
            if (index < 0) continue
            if (index < previousIndex) {
                alreadyOrdered = false
                break
            }
            previousIndex = index
        }
        if (alreadyOrdered) return
        // bringToFront() in ascending z order leaves the largest z at the end of the child list.
        // Do this only when the real child order drifted, so the 33 ms ticker stays cheap.
        for ((view, _) in ordered) {
            if (view.parent === root) view.bringToFront()
        }
        onLayerOrderChanged?.invoke()
    }

    private fun applyLive(view: View, layer: CustomLayerSpec) {
        view.translationZ = layer.z.coerceIn(0f, 1900f)
        if (layer.slot == "nav" && layer.dstSpecified && onNavRect != null) {
            onNavRect(
                layer.rect.left,
                layer.rect.top,
                layer.rect.width(),
                layer.rect.height(),
                layer.scale,
            )
            return
        }
        if (view is CustomBoxLiveView) {
            // The custom-theme ETA/song cards draw themselves into the layer's dst box; the view
            // itself is never scaled (their native size is the box, not a 430x165 design card).
            // No dst: pass 0 so the card keeps its native design size at the panel origin.
            val width = if (layer.dstSpecified) layer.rect.width() else 0f
            val height = if (layer.dstSpecified) layer.rect.height() else 0f
            view.setBox(layer.rect.left, layer.rect.top, width, height, layer.scale)
            view.x = 0f
            view.y = 0f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.rotation = 0f
            return
        }
        if (view is CustomResizableLiveView && layer.dstSpecified &&
            (layer.slot == "eta" || layer.slot == "song")
        ) {
            if (view is CustomPositionable) {
                view.setCustomPosition(layer.rect.left, layer.rect.top)
                view.x = 0f
                view.y = 0f
            } else {
                view.x = layer.rect.left
                view.y = layer.rect.top
            }
            // The card content is drawn at the view's absolute x/y. Scale around that point:
            // dst width/height then acts as a target box for the native 430x165 design card.
            val targetW = layer.rect.width().takeIf { it > 1f } ?: ETA_NATIVE_W
            val targetH = layer.rect.height().takeIf { it > 1f } ?: ETA_NATIVE_H
            view.pivotX = layer.rect.left
            view.pivotY = layer.rect.top
            view.scaleX = (targetW / ETA_NATIVE_W) * layer.scale
            view.scaleY = (targetH / ETA_NATIVE_H) * layer.scale
            if (layer.slot == "song") {
                android.util.Log.i(
                    "DiPlay-CustomTheme",
                    "song live dst=${layer.rect.left},${layer.rect.top} ${targetW}x$targetH " +
                        "dstSpecified=${layer.dstSpecified}",
                )
            }
            return
        }
        view.x = layer.rect.left
        view.y = layer.rect.top
        if (view is CustomPositionable) {
            view.setCustomPosition(layer.rect.left, layer.rect.top)
            view.x = 0f
            view.y = 0f
        }
        val scale = layer.scale
        view.scaleX = scale
        view.scaleY = scale
        if (view !is CustomPositionable) {
            // card112 is a fixed-aspect video window; its width/height stay native.
            view.pivotX = 0f
            view.pivotY = 0f
        }
    }

    /**
     * Live slots used to ignore keyframes: the animation list was only passed to the
     * self-drawn [CustomThemeLayerView]. A card112 x/y animation therefore played in the
     * desktop preview but not in the car. Apply the same keyframes to the real live views
     * here, using the base dst/scale/alpha as the fallback value.
     */
    private fun applyLiveAnimation(view: View, layer: CustomLayerSpec, clockMs: Long) {
        if (layer.slot != "card112" && layer.slot != "nav" &&
            layer.slot != "eta" && layer.slot != "song"
        ) return
        if ((layer.slot == "nav" || layer.slot == "eta" || layer.slot == "song") &&
            !layer.dstSpecified
        ) return
        val dx = animatedValue(layer.id, "x", clockMs, 0f)
        val dy = animatedValue(layer.id, "y", clockMs, 0f)
        val scaleAnim = animatedValue(layer.id, "scale", clockMs, 1f)
        val alphaAnim = animatedValue(layer.id, "alpha", clockMs, 1f)
        val rotation = animatedValue(layer.id, "rotation", clockMs, 0f)
        val alpha = (layer.alpha * alphaAnim).coerceIn(0f, 1f)
        if (view is CustomBoxLiveView) {
            // Animations move/scale the whole card around the middle of its box; the pivot is
            // established by setBox(), so it also works for a card without an explicit dst.
            view.translationX = dx
            view.translationY = dy
            view.scaleX = scaleAnim
            view.scaleY = scaleAnim
            view.alpha = alpha
            view.rotation = rotation
            return
        }
        when (layer.slot) {
            "card112" -> {
                // x/y come from the layer's dst plus the keyframe offset. Do NOT reset
                // translationX/Y afterwards: View.setX() already writes translationX, and the
                // old `translationX = 0` line cancelled every card112 position keyframe, so the
                // 112 card moved in the Theme Maker preview but never in the car
                // (field report 2026-11-11).
                view.x = layer.rect.left + dx
                view.y = layer.rect.top + dy
                // Scale/rotation around the card's own middle, the anchor the editor draws with.
                if (view.width > 0 && view.height > 0) {
                    view.pivotX = view.width / 2f
                    view.pivotY = view.height / 2f
                }
                view.scaleX = layer.scale * scaleAnim
                view.scaleY = layer.scale * scaleAnim
                view.alpha = alpha
                view.rotation = rotation
            }
            "eta", "song" -> {
                // Re-apply the anchor every tick: applyCardsVisibility/theme refreshes may
                // clear a custom position after attach, which used to leave the song card at
                // its persisted default position (under the map/112 card).
                if (view is CustomPositionable) {
                    view.setCustomPosition(layer.rect.left, layer.rect.top)
                    view.x = 0f
                    view.y = 0f
                }
                view.translationX = dx
                view.translationY = dy
                val targetW = layer.rect.width().takeIf { it > 1f } ?: ETA_NATIVE_W
                val targetH = layer.rect.height().takeIf { it > 1f } ?: ETA_NATIVE_H
                view.scaleX = (targetW / ETA_NATIVE_W) * layer.scale * scaleAnim
                view.scaleY = (targetH / ETA_NATIVE_H) * layer.scale * scaleAnim
                view.alpha = alpha
                view.rotation = rotation
            }
            "nav" -> {
                view.translationX = dx
                view.translationY = dy
                view.scaleX = layer.scale * scaleAnim
                view.scaleY = layer.scale * scaleAnim
                view.alpha = alpha
                view.rotation = rotation
            }
        }
    }

    private fun animatedValue(layerId: String, property: String, clockMs: Long, fallback: Float): Float {
        val specs = spec.animations.filter { it.target == layerId && it.property == property }
        if (specs.isEmpty()) return fallback
        var result = fallback
        for (animation in specs) {
            if (animation.durationMs <= 0L || animation.keyframes.isEmpty()) continue
            val phase = if (animation.loop) {
                (clockMs % animation.durationMs) / animation.durationMs.toFloat()
            } else {
                (clockMs / animation.durationMs.toFloat()).coerceIn(0f, 1f)
            }
            result = valueAt(animation, phase, fallback)
        }
        return result
    }

    private fun valueAt(animation: CustomAnimationSpec, phase: Float, fallback: Float): Float {
        val keys = animation.keyframes
        if (keys.isEmpty()) return fallback
        if (phase <= keys.first().first) return keys.first().second
        if (phase >= keys.last().first) return keys.last().second
        for (i in 0 until keys.lastIndex) {
            val a = keys[i]
            val b = keys[i + 1]
            if (phase in a.first..b.first) {
                val span = (b.first - a.first).coerceAtLeast(0.0001f)
                val t = (phase - a.first) / span
                return a.second + (b.second - a.second) * t
            }
        }
        return keys.last().second
    }

    private companion object {
        /** Native design box for the ETA and song data cards. */
        const val ETA_NATIVE_W = 430f
        const val ETA_NATIVE_H = 165f
    }
}
