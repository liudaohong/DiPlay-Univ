package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSupportTest {
    @Test
    fun requiresHardwareDecoderUnlessSoftwareExplicitlyChosen() {
        val swOnly = listOf(HevcSupport.Decoder("OMX.google.hevc.decoder", hardware = false))
        val hwAndSw = swOnly + HevcSupport.Decoder("OMX.MTK.VIDEO.DECODER.HEVC", hardware = true)

        assertFalse(HevcSupport.usable(requested = true, softwareChosen = false, decoders = swOnly))
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = true, decoders = swOnly))
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = false, decoders = hwAndSw))
        assertFalse(HevcSupport.usable(requested = false, softwareChosen = true, decoders = hwAndSw))
        assertFalse(HevcSupport.usable(requested = true, softwareChosen = true, decoders = emptyList()))
    }
}
