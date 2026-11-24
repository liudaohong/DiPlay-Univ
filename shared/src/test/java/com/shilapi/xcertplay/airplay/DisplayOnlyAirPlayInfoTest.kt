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
        @Suppress("UNCHECKED_CAST")
        val modes = info["modes"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val resources = modes["resources"] as List<Map<String, Any?>>
        assertTrue(resources.size == 1 && resources[0]["resourceID"] == 1)
    }
}
