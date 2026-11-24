package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayOnlyAirPlayInfoTest {
    @Test
    fun omitsAudioDeclarationsWhenAudioIsDisabled() {
        val config = AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = "AA:BB:CC:DD:EE:FF",
            btMac = "AA:BB:CC:DD:EE:FF",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 720),
            audio = false,
        )
        val info = AirPlayInfoPlist.build(config)
        assertFalse(info.containsKey("audioFormats"))
        assertFalse(info.containsKey("audioLatencies"))
        val fullFeatures = AirPlayInfoPlist.features(config.copy(audio = true))
        val displayOnlyFeatures = AirPlayInfoPlist.features(config)
        assertTrue(displayOnlyFeatures != fullFeatures)
        assertTrue((displayOnlyFeatures and 0x10004540a00L) == 0L)
        assertTrue(info["features"] == displayOnlyFeatures)
    }
}
