package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Guards the Ray theme's cheap PCM envelope: 120 BPM kicks become ~4 beats, not a buzz. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MusicReactiveBusTest {

    @Test
    fun kickTrainBecomesFourBeatsAndPauseStopsTheReactivity() {
        MusicReactiveBus.reset()
        MusicReactiveBus.setPlaying(true)
        val chunkMs = 20
        val chunks = 2_000 / chunkMs
        for (index in 0 until chunks) {
            val pcm = kickChunk(index * chunkMs, chunkMs)
            MusicReactiveBus.analyzePcm(pcm, 0, pcm.size, 2)
            ShadowSystemClock.advanceBy(Duration.ofMillis(chunkMs.toLong()))
        }

        val beats = MusicReactiveBus.beatCount
        assertTrue("expected a musical beat count, got $beats", beats in 3L..5L)
        assertTrue(MusicReactiveBus.pulseAt() > 0f)
        assertTrue(MusicReactiveBus.activeAt())

        MusicReactiveBus.setPlaying(false)
        assertFalse(MusicReactiveBus.activeAt())
        assertFalse(MusicReactiveBus.pulseAt() > 0f)

        MusicReactiveBus.setPlaying(true)
        assertTrue(MusicReactiveBus.activeAt())
        MusicReactiveBus.reset()
        assertFalse(MusicReactiveBus.activeAt())
    }

    /**
     * 48 kHz stereo, one 60 Hz kick at t=0, 500, 1000, 1500 ms with a short 120 ms decay.
     * Everything else is silence, so a detector that fires on the noise floor will stand out.
     */
    private fun kickChunk(startMs: Int, durationMs: Int): ByteArray {
        val sampleRate = 48_000
        val frames = sampleRate * durationMs / 1_000
        val bytes = ByteArray(frames * 2 * 2)
        for (frame in 0 until frames) {
            val timeMs = startMs + frame * 1_000.0 / sampleRate
            val beatIndex = kotlin.math.floor(timeMs / 500.0).toInt()
            val sinceBeat = timeMs - beatIndex * 500.0
            val value = if (sinceBeat in 0.0..120.0) {
                val envelope = exp(-sinceBeat / 45.0)
                (sin(2.0 * PI * 60.0 * sinceBeat / 1_000.0) * 24_000 * envelope).toInt()
            } else {
                0
            }
            for (channel in 0 until 2) {
                val index = (frame * 2 + channel) * 2
                bytes[index] = (value and 0xFF).toByte()
                bytes[index + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }
}
