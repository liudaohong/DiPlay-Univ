package com.shilapi.xcertplay

/**
 * Phase-4 rule engine (v1): one comparison per rule, held for `forMs`, optional hysteresis and
 * cooldown, then show/hide/flash actions. Pure JVM code so the timing can be unit tested.
 */
class CustomRuleEngine(private val rules: List<CustomRuleSpec>) {

    data class Frame(
        val hidden: Set<String>,
        val shown: Set<String>,
        val flashAlpha: Map<String, Float>,
        val pulseScale: Map<String, Float> = emptyMap(),
        val text: Map<String, String> = emptyMap(),
        val images: Map<String, String> = emptyMap(),
    ) {
        companion object {
            val EMPTY = Frame(emptySet(), emptySet(), emptyMap())
        }
    }

    private class State(conditionCount: Int) {
        val latched = BooleanArray(conditionCount)
        var trueSince = -1L
        var fired = false
        var firedAt = 0L
        var lastFire = Long.MIN_VALUE
    }

    private class SpeedSample(val at: Long, val value: Float)

    private val states = Array(rules.size) { State(rules[it].allConditions.size) }
    private val speedSamples = ArrayList<SpeedSample>()

    fun reset() {
        for (state in states) {
            state.latched.fill(false)
            state.trueSince = -1L
            state.fired = false
            state.firedAt = 0L
            state.lastFire = Long.MIN_VALUE
        }
        speedSamples.clear()
    }

    fun evaluate(data: ThemeData, nowMs: Long): Frame {
        if (rules.isEmpty()) return Frame.EMPTY
        val speed = data.speedKmh
        if (speed.isFinite()) {
            if (speedSamples.isEmpty() || nowMs > speedSamples[speedSamples.size - 1].at) {
                speedSamples.add(SpeedSample(nowMs, speed))
            }
            while (speedSamples.size > 2 && speedSamples[0].at < nowMs - 15_000L) {
                speedSamples.removeAt(0)
            }
        }
        val hidden = HashSet<String>()
        val shown = HashSet<String>()
        val flash = HashMap<String, Float>()
        val pulse = HashMap<String, Float>()
        val texts = HashMap<String, String>()
        val images = HashMap<String, String>()
        for (i in rules.indices) {
            val rule = rules[i]
            val state = states[i]
            val leaves = rule.allConditions
            val results = BooleanArray(leaves.size)
            for (j in leaves.indices) {
                val raw = valueFor(leaves[j], data, nowMs)
                results[j] = evalCondition(leaves[j], raw, state, j)
            }
            var condition = when (rule.combine) {
                "all" -> results.all { it }
                "any" -> results.any { it }
                else -> results.firstOrNull() == true
            }
            if (rule.negate) condition = !condition
            if (!condition) {
                state.trueSince = -1L
                state.fired = false
                continue
            }
            if (state.trueSince < 0L) state.trueSince = nowMs
            if (!state.fired &&
                nowMs - state.trueSince >= rule.forMs &&
                (state.lastFire == Long.MIN_VALUE || nowMs - state.lastFire >= rule.cooldownMs)
            ) {
                state.fired = true
                state.firedAt = nowMs
                state.lastFire = nowMs
            }
            if (!state.fired) continue
            val elapsed = (nowMs - state.firedAt).coerceAtLeast(0L)
            for (action in rule.actions) {
                if (action.durationMs > 0L && elapsed > action.durationMs) continue
                when (action.type) {
                    "hide" -> hidden += action.target
                    "show" -> shown += action.target
                    "flash" -> {
                        val on = (elapsed / action.periodMs) % 2L == 0L
                        val alpha = if (on) 1f else 0.25f
                        val previous = flash[action.target]
                        flash[action.target] = if (previous == null) alpha else minOf(previous, alpha)
                    }
                    "pulse" -> {
                        val phase = 2.0 * Math.PI * elapsed / action.periodMs.toDouble()
                        val factor = 1.0 + action.amplitude * Math.sin(phase)
                        pulse[action.target] = factor.toFloat().coerceIn(0.5f, 1.5f)
                    }
                    "setText" -> {
                        val value = action.text
                        if (!value.isNullOrEmpty()) texts[action.target] = value
                    }
                    "setImage" -> {
                        val value = action.imageSrc
                        if (!value.isNullOrEmpty()) images[action.target] = value
                    }
                }
            }
        }
        shown.removeAll(hidden)
        return Frame(hidden, shown, flash, pulse, texts, images)
    }

    private fun valueFor(c: CustomRuleCondition, data: ThemeData, nowMs: Long): Float? = when (c.source) {
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
        "speedDelta" -> speedDelta(data.speedKmh, nowMs, c.windowMs)
        else -> null
    }

    /** Current speed minus the interpolated speed `windowMs` ago; null until enough history. */
    private fun speedDelta(current: Float, nowMs: Long, windowMs: Long): Float? {
        if (!current.isFinite() || speedSamples.isEmpty()) return null
        val target = nowMs - windowMs
        var before: SpeedSample? = null
        var after: SpeedSample? = null
        for (sample in speedSamples) {
            if (sample.at <= target) before = sample
            else if (after == null) { after = sample; break }
        }
        val old = before ?: return null
        val value = if (after == null || after.at == old.at) {
            old.value
        } else {
            val fraction = (target - old.at).toDouble() / (after.at - old.at).toDouble()
            (old.value + (after.value - old.value) * fraction).toFloat()
        }
        return current - value
    }

    private fun evalCondition(c: CustomRuleCondition, value: Float?, state: State, index: Int): Boolean {
        if (value == null || !value.isFinite()) {
            state.latched[index] = false
            return false
        }
        var latched = state.latched[index]
        when (c.op) {
            "gt" -> if (latched) {
                if (value <= (c.release ?: c.value)) latched = false
            } else if (value > c.value) {
                latched = true
            }
            "gte" -> if (latched) {
                if (value < (c.release ?: c.value)) latched = false
            } else if (value >= c.value) {
                latched = true
            }
            "lt" -> if (latched) {
                if (value >= (c.release ?: c.value)) latched = false
            } else if (value < c.value) {
                latched = true
            }
            "lte" -> if (latched) {
                if (value > (c.release ?: c.value)) latched = false
            } else if (value <= c.value) {
                latched = true
            }
            "eq" -> latched = value == c.value
        }
        state.latched[index] = latched
        return latched
    }
}
