package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRedundancyTest {
    @Test
    fun `unpacks a redundant frame followed by the primary frame`() {
        val payload = byteArrayOf(
            0xE0.toByte(), 0x10, 0x00, 0x03, // F=1, PT=96, offset=1024, len=3
            1, 2, 3,
            0x60, 0x00, 0x00, 0x02, // F=0, PT=96, offset=0, len=2
            4, 5,
        )
        val frames = AudioRedundancy.unpack(payload, 96)!!
        assertEquals(2, frames.size)
        assertEquals(1024, frames[0].sampleOffset)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[0].payload)
        assertEquals(0, frames[1].sampleOffset)
        assertArrayEquals(byteArrayOf(4, 5), frames[1].payload)
    }

    @Test
    fun `raw AAC or Opus payload is left alone`() {
        assertNull(AudioRedundancy.unpack(byteArrayOf(0x21, 0x10, 0x04, 0x60), 96))
        assertNull(AudioRedundancy.unpack(byteArrayOf(0x60, 0x10, 0x04), 96))
    }

    @Test
    fun `truncated block is rejected`() {
        val payload = byteArrayOf(
            0xE0.toByte(), 0x10, 0x00, 0x08,
            1, 2, 3,
        )
        assertNull(AudioRedundancy.unpack(payload, 96))
    }
}
