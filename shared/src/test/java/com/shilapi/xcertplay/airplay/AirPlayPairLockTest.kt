package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class AirPlayPairLockTest {
    private fun testConfig(lockPaired: Boolean) = AirPlayConfig(
        deviceName = "DiPlay",
        deviceId = "AA:BB:CC:DD:EE:FF",
        btMac = "AA:BB:CC:DD:EE:FF",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 720, fps = 30),
        lockPairedDevices = lockPaired,
    )

    private fun testSession(
        identity: AirPlayIdentity,
        store: PairingStore,
        lockPaired: Boolean,
    ) = AirPlaySession(
        socket = Socket(),
        config = testConfig(lockPaired),
        identity = identity,
        pairings = store,
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    @Test
    fun lockModeAllowsInitialPairingWhenStoreIsEmptyThenRejectsStrangerPairSetup() {
        val identity = AirPlayIdentity.generate()
        val store = PairingStore()
        assertFalse(store.hasPairings())

        val m1 = Tlv8Codec.encode(listOf(Tlv8Item(0x00, byteArrayOf(0)), Tlv8Item(0x06, byteArrayOf(1))))
        val pairSetupReq = RtspMessage.Request(
            method = "POST",
            path = "/pair-setup",
            protocol = "RTSP/1.0",
            headers = mapOf("cseq" to "1"),
            body = m1,
        )

        // 1. Fresh install (empty store): pair-setup M1 is allowed even when lock is enabled.
        testSession(identity, store, lockPaired = true).use { emptySession ->
            val initialResp = emptySession.handle(pairSetupReq)
            val initialTlv = Tlv8Codec.decode(initialResp.body)
            assertEquals(2, initialTlv[0x06]?.firstOrNull()?.toInt())
            assertNull(initialTlv[0x07])
        }

        // 2. Once the owner's iPhone is in PairingStore, lock mode rejects new /pair-setup requests.
        val ownerLtpk = AirPlayIdentity.generate()
        store.save("owner-iphone-uuid", ownerLtpk.publicKey)
        assertTrue(store.hasPairings())

        testSession(identity, store, lockPaired = true).use { lockedSession ->
            val rejectedResp = lockedSession.handle(pairSetupReq)
            val rejectedTlv = Tlv8Codec.decode(rejectedResp.body)
            assertEquals(2, rejectedTlv[0x06]?.firstOrNull()?.toInt())
            assertEquals(2, rejectedTlv[0x07]?.firstOrNull()?.toInt()) // ERROR_AUTHENTICATION

            // 3. Meanwhile, /pair-verify with the already-paired owner's key succeeds!
            val clientEph = AirPlayCrypto.x25519Generate()
            val v1 = Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(1)),
                    Tlv8Item(0x03, clientEph.publicKey),
                ),
            )
            val v2Resp = lockedSession.handle(
                RtspMessage.Request("POST", "/pair-verify", "RTSP/1.0", mapOf("cseq" to "2"), v1),
            )
            val v2 = Tlv8Codec.decode(v2Resp.body)
            assertEquals(2, v2[0x06]?.firstOrNull()?.toInt())
            val serverEphPub = v2[0x03]
            assertNotNull(serverEphPub)

            val shared = AirPlayCrypto.x25519Shared(clientEph.privateKey, serverEphPub!!)
            val encKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.US_ASCII),
                "Pair-Verify-Encrypt-Info".toByteArray(Charsets.US_ASCII),
            )
            val ownerIdBytes = "owner-iphone-uuid".toByteArray(Charsets.UTF_8)
            val sig = AirPlayCrypto.ed25519Sign(
                ownerLtpk.privateKey,
                clientEph.publicKey + ownerIdBytes + serverEphPub,
            )
            val sub = Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x01, ownerIdBytes),
                    Tlv8Item(0x0a, sig),
                ),
            )
            val sealed = AirPlayCrypto.chachaSeal(encKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub)
            val v3 = Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(3)),
                    Tlv8Item(0x05, sealed),
                ),
            )
            val v4Resp = lockedSession.handle(
                RtspMessage.Request("POST", "/pair-verify", "RTSP/1.0", mapOf("cseq" to "3"), v3),
            )
            val v4 = Tlv8Codec.decode(v4Resp.body)
            assertEquals(4, v4[0x06]?.firstOrNull()?.toInt())
            assertNull(v4[0x07])
            assertTrue(lockedSession.pairVerify.isVerified)
        }

        // 4. With lock disabled, /pair-setup remains open as normal.
        testSession(identity, store, lockPaired = false).use { unlockedSession ->
            val openResp = unlockedSession.handle(pairSetupReq)
            val openTlv = Tlv8Codec.decode(openResp.body)
            assertEquals(2, openTlv[0x06]?.firstOrNull()?.toInt())
            assertNull(openTlv[0x07])
        }
    }
}
