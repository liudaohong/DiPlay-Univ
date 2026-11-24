package com.shilapi.xcertplay.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class KeystoreSecretBoxTest {
    private fun createJvmBox(): SecretBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SecretBox { key }
    }

    @Test
    fun encryptAndDecryptBytesRoundTripWithRandomizedIv() {
        val box = createJvmBox()
        val plain = "super-secret-private-key-material".toByteArray(Charsets.UTF_8)

        val enc1 = box.encryptBytes(plain)
        val enc2 = box.encryptBytes(plain)

        assertTrue(box.isEncryptedBytes(enc1))
        assertTrue(box.isEncryptedBytes(enc2))
        assertFalse(enc1.contentEquals(enc2))
        assertArrayEquals(plain, box.decryptBytes(enc1))
        assertArrayEquals(plain, box.decryptBytes(enc2))
    }

    @Test
    fun tamperedCiphertextFailsGcmAuthentication() {
        val box = createJvmBox()
        val enc = box.encryptBytes(byteArrayOf(1, 2, 3, 4, 5))
        enc[enc.lastIndex] = (enc[enc.lastIndex].toInt() xor 0x55).toByte()

        try {
            box.decryptBytes(enc)
            fail("Expected AEADBadTagException / GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
            // Expected.
        }
    }

    @Test
    fun legacyPlaintextStringMigratesInPlaceToEncryptedEnvelope() {
        val box = createJvmBox()
        var stored: String? = "UniV-Hotspot-2022"

        val firstRead = box.readOrMigrateString(stored) { migrated -> stored = migrated }
        assertEquals("UniV-Hotspot-2022", firstRead)
        assertTrue(box.isEncryptedString(stored))
        assertNotEquals("UniV-Hotspot-2022", stored)

        var migratedAgain = false
        val secondRead = box.readOrMigrateString(stored) { migratedAgain = true }
        assertEquals("UniV-Hotspot-2022", secondRead)
        assertFalse(migratedAgain)
    }

    @Test
    fun brokenKeystoreProviderFallsBackWithoutCrashing() {
        val broken = SecretBox { throw GeneralSecurityException("Keystore HAL unavailable") }
        assertEquals("fallback-passphrase", broken.writeStringOrPlain("fallback-passphrase"))
        assertEquals("legacy-secret", broken.readOrMigrateString("legacy-secret") {})
        assertNull(broken.decryptStringOrNull("${SecretBox.STRING_PREFIX}AAAA"))
    }
}
