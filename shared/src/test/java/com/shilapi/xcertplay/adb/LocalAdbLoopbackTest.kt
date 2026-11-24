package com.shilapi.xcertplay.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator

class LocalAdbLoopbackTest {
    @Test
    fun allowsStandardLoopbackTargetsOnly() {
        assertTrue(LocalAdb.isAllowedLoopbackHost("127.0.0.1"))
        assertTrue(LocalAdb.isAllowedLoopbackHost("::1"))
        assertTrue(LocalAdb.isAllowedLoopbackHost("localhost"))

        assertFalse(LocalAdb.isAllowedLoopbackHost(""))
        assertFalse(LocalAdb.isAllowedLoopbackHost("0.0.0.0"))
        assertFalse(LocalAdb.isAllowedLoopbackHost("192.168.43.1"))
        assertFalse(LocalAdb.isAllowedLoopbackHost("10.0.0.5"))
    }

    @Test
    fun constructorRejectsNonLoopbackHost() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        try {
            LocalAdb(pair, host = "192.168.1.10", port = 5555)
            fail("Expected IllegalArgumentException for LAN host")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
