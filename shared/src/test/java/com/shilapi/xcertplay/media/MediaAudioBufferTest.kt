package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `navigation buffer is selectable independently from music`() {
        // 48 kHz mono 16-bit = 96 000 bytes/s. 1000 ms capacity leaves an 800 ms start level.
        val plan = MediaAudioBuffer.plan(
            false, 48_000, 1, minBufferBytes = 1_920, mediaMillis = 1000, prompt = true,
            promptBufferMillis = 1000,
        )
        assertEquals(76_800, plan.startBytes)
        assertEquals(96_000, plan.trackBufferBytes)
        assertEquals(
            MediaAudioBuffer.plan(
                false, 48_000, 1, minBufferBytes = 1_920, mediaMillis = 300, prompt = true,
                promptBufferMillis = 1000,
            ),
            plan,
        )
    }

    @Test
    fun `larger music presets are selectable`() {
        assertTrue(2000 in MediaAudioBuffer.presets)
        assertTrue(3000 in MediaAudioBuffer.presets)
        assertEquals(2000, MediaAudioBuffer.sanitize(2000))
        assertEquals(600, MediaAudioBuffer.sanitizePrompt(600))
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(
            192_000L * MediaAudioBuffer.DEFAULT_MILLIS / 1000,
            MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes.toLong(),
        )
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }
}
