package com.shilapi.xcertplay

import java.util.Locale

/**
 * Expands a text-layer template such as `"{speed:0} km/h  {range:1} km"`.
 *
 * Supported sources: speed, rpm, odo, range, clock, night, appleFullMap, navActive,
 * navRemainingKm, navRemainingMin, navArrival, navDelta, navSubtitle, songTitle, songArtist.
 * A numeric source accepts `:{precision}` (0–6). Any unavailable source makes the whole layer
 * fall back to the static `text`, so a theme never shows a half-empty or `NaN` string.
 */
internal object ThemeTextFormat {
    // Android's ICU regex rejects a bare closing brace ("Unmatched closing '}'"), while the
    // desktop JVM tolerates it, so the trailing brace must be escaped. The phone smoke test
    // caught this as a PatternSyntaxException at ThemeTextFormat.<clinit> (2026-11-12).
    private val TOKEN = Regex("""\{([A-Za-z][A-Za-z0-9_]*)(?::(\d{1,2}))?\}""")

    fun format(template: String, data: ThemeData, fallback: String): String {
        if (template.isEmpty()) return fallback
        var missing = false
        val text = TOKEN.replace(template) { match ->
            val source = match.groupValues[1]
            val precision = (match.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 6)
            when (val value = valueOf(source, data)) {
                null -> {
                    missing = true
                    ""
                }
                is Float -> String.format(Locale.US, "%.${precision}f", value)
                is Double -> String.format(Locale.US, "%.${precision}f", value)
                is Int -> value.toString()
                else -> value.toString()
            }
        }
        return if (missing) fallback else text
    }

    /** Null means "this source has no value right now". */
    fun valueOf(source: String, data: ThemeData): Any? = when (source) {
        "speed" -> data.speedKmh.finiteOrNull()
        "rpm" -> data.rpm.finiteOrNull()
        "odo" -> data.odometerKm.finiteOrNull()
        "range" -> data.rangeKm.finiteOrNull()
        "clock" -> data.clockText.takeIf { it.isNotBlank() }
        "night" -> if (data.night) 1 else 0
        "appleFullMap" -> if (data.appleFullMap) 1 else 0
        "navActive" -> if (data.navActive) 1 else 0
        "navRemainingKm" -> data.navRemainingKm.ifBlank { null }
        "navRemainingMin" -> data.navRemainingMin.ifBlank { null }
        "navArrival" -> data.navArrival.ifBlank { null }
        "navDelta" -> data.navDelta.ifBlank { null }
        "navSubtitle" -> data.navSubtitle.ifBlank { null }
        "songTitle" -> data.songTitle.ifBlank { null }
        "songArtist" -> data.songArtist.ifBlank { null }
        "gear" -> data.gear.takeIf { it > 0 }
        "gearRaw" -> data.gearRaw.takeIf { it > 0 }
        "gearText" -> data.gearText.ifBlank { null }
        "turnLeft" -> if (data.turnLeft) 1 else 0
        "turnRight" -> if (data.turnRight) 1 else 0
        "hazard" -> if (data.hazard) 1 else 0
        "turnLeftBlink" -> if (data.turnLeftBlink) 1 else 0
        "turnRightBlink" -> if (data.turnRightBlink) 1 else 0
        "audioLevel" -> data.audioLevel
        "audioBass" -> data.audioBass
        "beatPulse" -> data.beatPulse
        else -> null
    }
}
