package com.shilapi.xcertplay.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.shilapi.xcertplay.compat.Base64Compat
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware/TEE-backed AES-256-GCM envelope for local secrets at rest (ADB private key, AirPlay and
 * Lockdown long-term private keys, hotspot/Wi-Fi passphrases, remote MFi token).
 *
 * Designed for car head units (Android 7.1+ / UNI-V Android 9):
 *  - No lockscreen/user-authentication requirement, so headless boot receivers can decrypt.
 *  - Caches the Keystore key handle and retries once on transient Binder/HAL hiccups.
 *  - Versioned magic prefixes allow zero-loss in-place migration from legacy plaintext files/prefs.
 *  - Degrades safely if a custom head-unit ROM ships a broken AndroidKeyStore HAL.
 */
class SecretBox(
    private val keyProvider: () -> SecretKey,
    private val onKeyRetryReset: () -> Unit,
) {
    constructor(keyProvider: () -> SecretKey) : this(keyProvider, {})
    fun isEncryptedBytes(data: ByteArray): Boolean =
        data.size > HEADER_FIXED_BYTES &&
            data[0] == MAGIC_0 &&
            data[1] == MAGIC_1 &&
            data[2] == MAGIC_2 &&
            data[3] == VERSION_1

    fun isEncryptedString(value: String?): Boolean =
        value != null && value.startsWith(STRING_PREFIX)

    @Throws(GeneralSecurityException::class)
    fun encryptBytes(plaintext: ByteArray): ByteArray = withSingleKeyRetry {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv ?: throw GeneralSecurityException("Cipher did not provide GCM IV")
        if (iv.size !in MIN_IV_BYTES..MAX_IV_BYTES) {
            throw GeneralSecurityException("Unexpected GCM IV length: ${iv.size}")
        }
        val ciphertext = cipher.doFinal(plaintext)
        val out = ByteArray(HEADER_FIXED_BYTES + iv.size + ciphertext.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = MAGIC_2
        out[3] = VERSION_1
        out[4] = iv.size.toByte()
        System.arraycopy(iv, 0, out, HEADER_FIXED_BYTES, iv.size)
        System.arraycopy(ciphertext, 0, out, HEADER_FIXED_BYTES + iv.size, ciphertext.size)
        out
    }

    @Throws(GeneralSecurityException::class)
    fun decryptBytes(envelope: ByteArray): ByteArray {
        if (!isEncryptedBytes(envelope)) {
            throw GeneralSecurityException("Missing DPK1 envelope header")
        }
        val ivLen = envelope[4].toInt() and 0xff
        if (ivLen !in MIN_IV_BYTES..MAX_IV_BYTES || envelope.size < HEADER_FIXED_BYTES + ivLen + GCM_TAG_BYTES) {
            throw GeneralSecurityException("Truncated DPK1 envelope")
        }
        val iv = envelope.copyOfRange(HEADER_FIXED_BYTES, HEADER_FIXED_BYTES + ivLen)
        val ciphertext = envelope.copyOfRange(HEADER_FIXED_BYTES + ivLen, envelope.size)
        return withSingleKeyRetry {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }
    }

    fun encryptStringOrNull(plaintext: String): String? = try {
        val encrypted = encryptBytes(plaintext.toByteArray(Charsets.UTF_8))
        STRING_PREFIX + Base64Compat.encode(encrypted)
    } catch (error: Throwable) {
        logWarn("encryptString failed; falling back", error)
        null
    }

    fun decryptStringOrNull(stored: String): String? {
        if (!isEncryptedString(stored)) return stored
        return try {
            val raw = Base64Compat.decode(stored.substring(STRING_PREFIX.length))
            String(decryptBytes(raw), Charsets.UTF_8)
        } catch (error: Throwable) {
            logWarn("decryptString failed", error)
            null
        }
    }

    /** Encrypts non-empty strings when Keystore is available; degrades to plaintext only on HAL failure. */
    fun writeStringOrPlain(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return encryptStringOrNull(plaintext) ?: plaintext
    }

    /**
     * Reads a secret string that may be encrypted (`__dp_enc_v1__:...`) or legacy plaintext.
     * Legacy plaintext is transparently re-encrypted via [persistMigrated] on first read.
     */
    fun readOrMigrateString(stored: String?, persistMigrated: (String) -> Unit): String? {
        if (stored == null || stored.isEmpty()) return stored
        if (isEncryptedString(stored)) {
            return decryptStringOrNull(stored)
        }
        encryptStringOrNull(stored)?.let { migrated ->
            runCatching { persistMigrated(migrated) }
        }
        return stored
    }

    private inline fun <T> withSingleKeyRetry(block: () -> T): T {
        return try {
            block()
        } catch (authFail: AEADBadTagException) {
            // Ciphertext tampered or wrong key: do not hide authentication failure.
            throw authFail
        } catch (_: Throwable) {
            // Transient Keystore/TEE IPC hiccup on early boot: drop cached handle and retry once.
            runCatching { onKeyRetryReset() }
            block()
        }
    }

    private fun logWarn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, "$message: ${error.javaClass.simpleName}: ${error.message}") }
    }

    companion object {
        private const val TAG = "DiPlay-SecretBox"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 16
        private const val HEADER_FIXED_BYTES = 5
        private const val MAGIC_0: Byte = 0x44 // 'D'
        private const val MAGIC_1: Byte = 0x50 // 'P'
        private const val MAGIC_2: Byte = 0x4B // 'K'
        private const val VERSION_1: Byte = 0x01
        const val STRING_PREFIX = "__dp_enc_v1__:"
    }
}

object KeystoreSecretBox {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "xcertplay_master_key_v1"

    @Volatile private var cachedMasterKey: SecretKey? = null

    val default: SecretBox = SecretBox(
        keyProvider = ::getOrCreateMasterKey,
        onKeyRetryReset = { cachedMasterKey = null },
    )

    @Synchronized
    private fun getOrCreateMasterKey(): SecretKey {
        cachedMasterKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            cachedMasterKey = existing
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey().also { cachedMasterKey = it }
    }
}
