package com.shilapi.xcertplay

/**
 * Phase-5 state machine. A state matches by its single condition; array order wins. A state
 * without `when` is the fallback ("base") and is used only when nothing else matches.
 * The frame is an override map: absent fields mean "keep the layer's own value".
 */
class CustomStateMachine(
    private val states: List<CustomStateSpec>,
    private val baseRects: Map<String, StateRect> = emptyMap(),
) {

    data class Frame(
        val activeId: String?,
        val visible: Map<String, Boolean>,
        val alpha: Map<String, Float>,
        val scale: Map<String, Float>,
        val x: Map<String, Float>,
        val y: Map<String, Float>,
        val color: Map<String, Int>,
        val dst: Map<String, StateRect>,
    ) {
        companion object {
            val EMPTY = Frame(null, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    private val latched = BooleanArray(states.size)
    private var current = -1
    private var from = -1
    private var transitionStart = 0L
    private var transitionMs = 0L
    private var introActive = false
    private var introIndex = -1
    private var introUntilMs = 0L

    fun playIntro(stateId: String, holdMs: Long, nowMs: Long = 0L) {
        val index = states.indexOfFirst { it.id == stateId }
        if (index < 0) return
        introActive = true
        introIndex = index
        introUntilMs = nowMs + holdMs.coerceIn(0L, 5000L)
        current = index
        from = -1
        transitionMs = 0L
    }

    fun reset() {
        latched.fill(false)
        current = -1
        from = -1
        transitionStart = 0L
        transitionMs = 0L
        introActive = false
        introIndex = -1
        introUntilMs = 0L
    }

    fun evaluate(data: ThemeData, nowMs: Long): Frame {
        if (states.isEmpty()) return Frame.EMPTY
        if (introActive) {
            if (nowMs < introUntilMs) {
                val intro = states[introIndex]
                return frameOf(intro, intro.id)
            }
            introActive = false
        }
        var target = -1
        var fallback = -1
        for (i in states.indices) {
            val state = states[i]
            if (state.condition == null) {
                fallback = i
                continue
            }
            if (matches(state.condition, data, i)) {
                target = i
                break
            }
        }
        if (target < 0) target = fallback
        if (target < 0) return Frame.EMPTY
        if (target != current) {
            if (current < 0) {
                current = target
                from = -1
                transitionMs = 0L
            } else {
                from = current
                current = target
                transitionStart = nowMs
                transitionMs = states[target].transitionMs
            }
        }
        val active = states[current]
        if (from >= 0 && nowMs - transitionStart < transitionMs) {
            val raw = if (transitionMs <= 0L) 1f
            else ((nowMs - transitionStart).toFloat() / transitionMs).coerceIn(0f, 1f)
            val f = raw * raw * (3f - 2f * raw)
            return blend(states[from], active, f)
        }
        from = -1
        return frameOf(active, active.id)
    }

    private fun matches(c: CustomRuleCondition, data: ThemeData, index: Int): Boolean {
        val value = valueFor(c.source, data)
        if (value == null || !value.isFinite()) {
            latched[index] = false
            return false
        }
        var on = latched[index]
        when (c.op) {
            "gt" -> if (on) {
                if (value <= (c.release ?: c.value)) on = false
            } else if (value > c.value) on = true
            "gte" -> if (on) {
                if (value < (c.release ?: c.value)) on = false
            } else if (value >= c.value) on = true
            "lt" -> if (on) {
                if (value >= (c.release ?: c.value)) on = false
            } else if (value < c.value) on = true
            "lte" -> if (on) {
                if (value > (c.release ?: c.value)) on = false
            } else if (value <= c.value) on = true
            "eq" -> on = value == c.value
        }
        latched[index] = on
        return on
    }

    private fun valueFor(source: String, data: ThemeData): Float? = when (source) {
        "speed" -> data.speedKmh
        "rpm" -> data.rpm
        "odo" -> data.odometerKm
        "range" -> data.rangeKm
        "night" -> if (data.night) 1f else 0f
        "appleFullMap" -> if (data.appleFullMap) 1f else 0f
        "navActive" -> if (data.navActive) 1f else 0f
        "songPlaying" -> if (data.songTitle.isNotEmpty()) 1f else 0f
        "gear" -> data.gear.toFloat()
        "gearRaw" -> data.gearRaw.toFloat()
        "turnLeft" -> if (data.turnLeft) 1f else 0f
        "turnRight" -> if (data.turnRight) 1f else 0f
        "hazard" -> if (data.hazard) 1f else 0f
        "turnLeftBlink" -> if (data.turnLeftBlink) 1f else 0f
        "turnRightBlink" -> if (data.turnRightBlink) 1f else 0f
        "audioLevel" -> data.audioLevel
        "audioBass" -> data.audioBass
        "beatPulse" -> data.beatPulse
        else -> null
    }

    private fun frameOf(state: CustomStateSpec, activeId: String?): Frame {
        val visible = HashMap<String, Boolean>()
        val alpha = HashMap<String, Float>()
        val scale = HashMap<String, Float>()
        val x = HashMap<String, Float>()
        val y = HashMap<String, Float>()
        val color = HashMap<String, Int>()
        val dst = HashMap<String, StateRect>()
        for ((layerId, o) in state.overrides) {
            o.visible?.let { visible[layerId] = it }
            alpha[layerId] = o.alpha
            scale[layerId] = o.scale
            x[layerId] = o.x
            y[layerId] = o.y
            o.color?.let { color[layerId] = it }
            o.dst?.let { dst[layerId] = it }
        }
        return Frame(activeId, visible, alpha, scale, x, y, color, dst)
    }

    private fun blend(fromState: CustomStateSpec, toState: CustomStateSpec, f: Float): Frame {
        val layers = LinkedHashSet<String>()
        layers.addAll(fromState.overrides.keys)
        layers.addAll(toState.overrides.keys)
        val visible = HashMap<String, Boolean>()
        val alpha = HashMap<String, Float>()
        val scale = HashMap<String, Float>()
        val x = HashMap<String, Float>()
        val y = HashMap<String, Float>()
        val color = HashMap<String, Int>()
        val dst = HashMap<String, StateRect>()
        for (layerId in layers) {
            val a = fromState.overrides[layerId]
            val b = toState.overrides[layerId]
            val av = a?.visible
            val bv = b?.visible
            val v = when {
                av == false && bv == false -> false
                av == true || bv == true -> true
                else -> null
            }
            v?.let { visible[layerId] = it }
            alpha[layerId] = lerp(a?.alpha ?: 1f, b?.alpha ?: 1f, f)
            scale[layerId] = lerp(a?.scale ?: 1f, b?.scale ?: 1f, f)
            x[layerId] = lerp(a?.x ?: 0f, b?.x ?: 0f, f)
            y[layerId] = lerp(a?.y ?: 0f, b?.y ?: 0f, f)
            val ac = a?.color
            val bc = b?.color
            if (ac != null && bc != null) color[layerId] = blendColor(ac, bc, f)
            else (ac ?: bc)?.let { color[layerId] = it }
            val fromDst = a?.dst ?: baseRects[layerId]
            val toDst = b?.dst ?: baseRects[layerId]
            if (fromDst != null && toDst != null) {
                dst[layerId] = StateRect(
                    lerp(fromDst.x, toDst.x, f), lerp(fromDst.y, toDst.y, f),
                    lerp(fromDst.w, toDst.w, f), lerp(fromDst.h, toDst.h, f),
                )
            } else (toDst ?: fromDst)?.let { dst[layerId] = it }
        }
        return Frame(toState.id, visible, alpha, scale, x, y, color, dst)
    }

    private fun blendColor(a: Int, b: Int, f: Float): Int {
        val aa = (a ushr 24) and 0xFF
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val ba = (b ushr 24) and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val outA = (aa + (ba - aa) * f).toInt().coerceIn(0, 255)
        val outR = (ar + (br - ar) * f).toInt().coerceIn(0, 255)
        val outG = (ag + (bg - ag) * f).toInt().coerceIn(0, 255)
        val outB = (ab + (bb - ab) * f).toInt().coerceIn(0, 255)
        return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}
