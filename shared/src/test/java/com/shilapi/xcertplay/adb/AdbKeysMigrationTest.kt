package com.shilapi.xcertplay.adb

import com.shilapi.xcertplay.security.SecretBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import javax.crypto.KeyGenerator

class AdbKeysMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun createJvmBox(): SecretBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SecretBox { key }
    }

    @Test
    fun freshKeyPairIsEncryptedAtRestAndReloadsIdentically() {
        val dir = temp.newFolder("adb")
        val box = createJvmBox()

        val created = AdbKeys.loadFromDir(dir, box)
        val rawPrivateOnDisk = File(dir, "adbkey.private").readBytes()
        assertTrue(box.isEncryptedBytes(rawPrivateOnDisk))
        assertFalse(rawPrivateOnDisk.contentEquals(created.private.encoded))

        val reloaded = AdbKeys.loadFromDir(dir, box)
        assertArrayEquals(created.public.encoded, reloaded.public.encoded)
        assertArrayEquals(created.private.encoded, reloaded.private.encoded)

        val token = ByteArray(20) { it.toByte() }
        assertArrayEquals(AdbKeys.sign(token, created.private), AdbKeys.sign(token, reloaded.private))
    }

    @Test
    fun legacyPlaintextPkcs8MigratesInPlaceWithoutChangingApprovedKey() {
        val dir = temp.newFolder("adb-legacy")
        val legacy = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        File(dir, "adbkey.private").writeBytes(legacy.private.encoded)
        File(dir, "adbkey.public").writeBytes(legacy.public.encoded)

        val box = createJvmBox()
        val migrated = AdbKeys.loadFromDir(dir, box)
        assertArrayEquals(legacy.public.encoded, migrated.public.encoded)
        assertArrayEquals(legacy.private.encoded, migrated.private.encoded)
        assertTrue(box.isEncryptedBytes(File(dir, "adbkey.private").readBytes()))
    }

    @Test
    fun corruptedEncryptedKeyRegeneratesCleanly() {
        val dir = temp.newFolder("adb-corrupt")
        val box = createJvmBox()
        val first = AdbKeys.loadFromDir(dir, box)

        val privateFile = File(dir, "adbkey.private")
        val tampered = privateFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte() }
        privateFile.writeBytes(tampered)

        val regenerated = AdbKeys.loadFromDir(dir, box)
        assertNotNull(regenerated)
        assertFalse(first.public.encoded.contentEquals(regenerated.public.encoded))
        assertTrue(box.isEncryptedBytes(privateFile.readBytes()))
    }
}
