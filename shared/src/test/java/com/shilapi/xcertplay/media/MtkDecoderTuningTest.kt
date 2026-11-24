package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtkDecoderTuningTest {
    @Test
    fun selectsCodec2LowLatencyOverLegacyOmx() {
        val selected = MtkDecoderTuning.selectedDecoder(
            listOf(
                MtkDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC", lowLatency = false),
                MtkDecoderCandidate("c2.mtk.avc.decoder.lowlatency", lowLatency = true),
            ),
        )
        assertEquals("c2.mtk.avc.decoder.lowlatency", selected?.name)
    }

    @Test
    fun legacyOmxSupportsAcodecKeysAndFallbackOrder() {
        assertTrue(MtkDecoderTuning.supportsLegacyAcodecKeys("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(MtkDecoderTuning.supportsLegacyAcodecKeys("c2.mtk.avc.decoder"))
        assertEquals(
            listOf(MtkDecoderMode.LOW_LATENCY_NO_REORDER, MtkDecoderMode.LOW_LATENCY, MtkDecoderMode.NONE),
            MtkDecoderMode.LOW_LATENCY_NO_REORDER.attempts(),
        )
        assertEquals(
            listOf(MtkDecoderTuning.LOW_LATENCY_KEY, MtkDecoderTuning.NO_REORDER_KEY),
            appliedMtkDecoderMode(MtkDecoderMode.LOW_LATENCY_NO_REORDER, "OMX.MTK.VIDEO.DECODER.AVC").formatKeys(),
        )
        assertEquals(
            emptyList<String>(),
            appliedMtkDecoderMode(MtkDecoderMode.LOW_LATENCY_NO_REORDER, "OMX.google.h264.decoder").formatKeys(),
        )
    }
}
