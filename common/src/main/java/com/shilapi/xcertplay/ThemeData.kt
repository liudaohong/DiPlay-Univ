package com.shilapi.xcertplay

import java.util.Locale
import java.util.TimeZone

/**
 * One sampled frame of every value a custom theme can read.
 *
 * The host samples this once per tick and hands the same instance to every layer, so a gauge,
 * a text binding and a visibility condition all agree on one frame instead of each reading the
 * vehicle bus at a slightly different moment. Tests can inject a snapshot directly.
 */
data class ThemeData(
    val speedKmh: Float = Float.NaN,
    val rpm: Float = Float.NaN,
    val odometerKm: Float = Float.NaN,
    val rangeKm: Float = Float.NaN,
    val night: Boolean = false,
    val appleFullMap: Boolean = false,
    /** Local wall clock, minutes since midnight (0..1439). */
    val clockMinutes: Int = 0,
    /** Local wall clock formatted HH:mm; text templates use this snapshot, not a live clock. */
    val clockText: String = "",
    val navActive: Boolean = false,
    val navRemainingKm: String = "",
    val navRemainingMin: String = "",
    val navArrival: String = "",
    val navDelta: String = "",
    val navSubtitle: String = "",
    val songTitle: String = "",
    val songArtist: String = "",
    /** Canonical gear: 0 unknown, 1=P, 2=R, 3=N, 4=D, 5..10=M1..M6. */
    val gear: Int = 0,
    val gearRaw: Int = 0,
    val gearText: String = "",
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val hazard: Boolean = false,
    val turnLeftBlink: Boolean = false,
    val turnRightBlink: Boolean = false,
    /** Music-channel PCM level/bass/beat; 0 when paused or no PCM. */
    val audioLevel: Float = 0f,
    val audioBass: Float = 0f,
    val beatPulse: Float = 0f,
) {
    companion object {
        /** Time-zone aware minutes since local midnight; avoids java.time on minSdk 25. */
        fun localMinutes(nowMs: Long = System.currentTimeMillis()): Int {
            val offset = TimeZone.getDefault().getOffset(nowMs)
            val minutes = ((nowMs + offset) / 60_000L % 1_440L).toInt()
            return if (minutes < 0) minutes + 1_440 else minutes
        }

        fun clockText(minutes: Int): String {
            val safe = ((minutes % 1_440) + 1_440) % 1_440
            return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
        }
    }
}

/** Finite-or-NaN as nullable, used by the text formatter. */
internal fun Float.finiteOrNull(): Float? = if (isFinite()) this else null

/** Old visibleIf speed/rpm behaviour kept for compatibility: NaN behaves like 0. */
internal fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f
