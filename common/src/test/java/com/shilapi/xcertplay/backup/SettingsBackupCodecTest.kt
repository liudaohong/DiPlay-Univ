package com.shilapi.xcertplay.backup

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SettingsBackupCodecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun roundTripsChanganPreferencesAndNeverExportsSecrets() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("mtk_decoder_tuning", true)
            .putBoolean("ambient_rhythm", true)
            .putBoolean("auto_bluetooth", true)
            .putLong("auto_bluetooth_off_delay_ms", 10_000L)
            .putBoolean("voice_key_short_siri_off", true)
            .putInt("nav_duck_percent", 75)
            .putInt("navigation_buffer_ms", 1500)
            .putString("cluster_porthole_theme", "red_white_sport")
            .putString("cluster_theme_dir", "/data/local/tmp/themes")
            .putInt("cluster_turn_card_x_px", 120)
            // Sensitive credentials must NEVER be exported.
            .putString("identity_private", "SECRET_PRIVATE_KEY")
            .putString("pairing.iphone1", "SECRET_PAIRING")
            .putString("manual_hotspot_passphrase", "SECRET_WIFI_PASS")
            .commit()

        val exported = SettingsBackupCodec.export(context, "0.2.30")
        val text = exported.toString(Charsets.UTF_8)
        assertFalse(text.contains("SECRET_PRIVATE_KEY"))
        assertFalse(text.contains("SECRET_PAIRING"))
        assertFalse(text.contains("SECRET_WIFI_PASS"))

        // Clear all prefs and re-import.
        prefs.edit().clear().commit()
        val outcome = SettingsBackupCodec.import(context, exported)
        assertFalse(outcome.invalid)
        assertTrue(outcome.applied >= 10)

        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
        assertTrue(prefs.getBoolean("ambient_rhythm", false))
        assertTrue(prefs.getBoolean("auto_bluetooth", false))
        assertEquals(10_000L, prefs.getLong("auto_bluetooth_off_delay_ms", -1L))
        assertTrue(prefs.getBoolean("voice_key_short_siri_off", false))
        assertEquals(75, prefs.getInt("nav_duck_percent", -1))
        assertEquals(1500, prefs.getInt("navigation_buffer_ms", -1))
        assertEquals("red_white_sport", prefs.getString("cluster_porthole_theme", null))
        assertEquals("/data/local/tmp/themes", prefs.getString("cluster_theme_dir", null))
        assertEquals(120, prefs.getInt("cluster_turn_card_x_px", -1))
        assertNull(prefs.getString("identity_private", null))
    }
}
