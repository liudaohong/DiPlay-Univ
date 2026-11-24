package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LostMusicFillTest {
    @Test
    fun fillsBoundedSilenceForDroppedRtpSequenceGap() {
        val gap = RtpSequenceGap()
        val fill = LostMusicFill(sampleRate = 48_000)

        fun rtp(seq: Int): ByteArray = byteArrayOf(
            0x80.toByte(),
            0x60.toByte(),
            ((seq ushr 8) and 0xff).toByte(),
            (seq and 0xff).toByte(),
        )

        assertEquals(0, gap.lostBefore(rtp(100)))
        assertEquals(0, fill.silenceBefore(ptsUs = 0L, frames = 1024, maxFrames = 8192))

        // Packets 101 and 102 were lost on Wi-Fi; packet 103 arrives 3 frames later.
        val lost = gap.lostBefore(rtp(103))
        assertEquals(2, lost)
        val pts103 = 3L * 1024L * 1_000_000L / 48_000L
        fill.lossBefore(pts103, lost)

        val silence = fill.silenceBefore(ptsUs = pts103, frames = 1024, maxFrames = 8192)
        assertEquals(2048, silence)
    }

    @Test
    fun voiceFilterAttenuatesLowFrequencyDcAndPassesSpeech() {
        val filter = VoiceFilter(sampleRate = 16_000, channels = 1)
        val pcm = ByteArray(1600 * 2)
        // Constant DC / sub-bass signal should decay strongly through 200Hz high-pass filter.
        for (i in 0 until 1600) {
            val v = 10_000
            pcm[2 * i] = (v and 0xff).toByte()
            pcm[2 * i + 1] = ((v ushr 8) and 0xff).toByte()
        }
        filter.process(pcm, 0, pcm.size)
        val tailSample = ((pcm[pcm.size - 1].toInt() shl 8) or (pcm[pcm.size - 2].toInt() and 0xff)).toShort()
        assertTrue("Expected DC tail to be attenuated, was $tailSample", kotlin.math.abs(tailSample.toInt()) < 500)
    }

    @Test
    fun echoReferenceReturnsBufferedFarEndAudio() {
        val ref = EchoReference(sampleRate = 16_000, capacityMillis = 1000)
        val pcm = ByteArray(320 * 2)
        for (i in 0 until 320) {
            val v = 4000
            pcm[2 * i] = (v and 0xff).toByte()
            pcm[2 * i + 1] = ((v ushr 8) and 0xff).toByte()
        }
        val t0 = 1_000_000_000L
        ref.append(pcm, 0, pcm.size, channels = 1, pendingFrames = 0L, nowNs = t0)
        val out = ShortArray(160)
        assertTrue(ref.read(out, endNs = t0))
        assertTrue(out.any { it.toInt() == 4000 })
    }
}
