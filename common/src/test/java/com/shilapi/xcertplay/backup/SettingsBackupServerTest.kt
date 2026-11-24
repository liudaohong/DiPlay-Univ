package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.airplay.RtspMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class SettingsBackupServerTest {
    @Test
    fun servesSettingsExportAndDiagnosticReportWith128BitTokenAndSecurityHeaders() {
        val server = SettingsBackupServer(
            page = "<html>ok</html>".toByteArray(),
            export = { """{"format":"diplay-settings","version":1,"settings":{}}""".toByteArray() },
            import = { SettingsImportOutcome(applied = 1, skipped = 0, invalid = false) },
            report = { "DiPlay Changan diagnostic report".toByteArray() },
            codePage = "<html>code</html>".toByteArray(),
        ).apply {
            typedPort = 0
        }
        val endpoint = server.start()
        assertNotNull(endpoint)
        assertEquals(32, endpoint!!.token.length)
        try {
            val exportConn = URL("${endpoint.url("127.0.0.1")}export").openConnection() as HttpURLConnection
            assertEquals(200, exportConn.responseCode)
            assertEquals("nosniff", exportConn.getHeaderField("X-Content-Type-Options"))
            assertEquals("no-referrer", exportConn.getHeaderField("Referrer-Policy"))
            val exportBody = exportConn.inputStream.bufferedReader().readText()
            assertTrue(exportBody.contains("diplay-settings"))

            val reportConn = URL("${endpoint.url("127.0.0.1")}report").openConnection() as HttpURLConnection
            assertEquals(200, reportConn.responseCode)
            val reportBody = reportConn.inputStream.bufferedReader().readText()
            assertEquals("DiPlay Changan diagnostic report", reportBody)
        } finally {
            server.stop()
        }
    }

    @Test
    fun perIpCodeRateLimitDoesNotLockOutOwnerPhoneIp() {
        val server = SettingsBackupServer(
            page = "<html>ok</html>".toByteArray(),
            export = { ByteArray(0) },
            import = { SettingsImportOutcome(applied = 0, skipped = 0, invalid = false) },
            codePage = "<html>code</html>".toByteArray(),
        ).apply { typedPort = 0 }
        val endpoint = server.start()!!
        try {
            repeat(SettingsBackupServer.MAX_WRONG_CODES) {
                val (status, _, _) = server.checkCode("000000".takeIf { it != endpoint.code }?.toByteArray() ?: "999999".toByteArray(), "192.168.43.99")
                assertEquals(403, status)
            }
            // Bystander IP is locked out even with the right code:
            val (lockedStatus, _, lockedBody) = server.checkCode(endpoint.code.toByteArray(), "192.168.43.99")
            assertEquals(403, lockedStatus)
            assertTrue(String(lockedBody).contains("locked"))

            // Owner's phone on a different LAN IP can still enter the valid code:
            val (ownerStatus, _, ownerBody) = server.checkCode(endpoint.code.toByteArray(), "192.168.43.10")
            assertEquals(200, ownerStatus)
            assertTrue(String(ownerBody).contains("/b/${endpoint.token}/"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsCrossOriginPostAndPublicWanAddresses() {
        val server = SettingsBackupServer(
            page = "<html>ok</html>".toByteArray(),
            export = { ByteArray(0) },
            import = { SettingsImportOutcome(applied = 1, skipped = 0, invalid = false) },
            codePage = "<html>code</html>".toByteArray(),
        ).apply { typedPort = 0 }
        val endpoint = server.start()!!
        try {
            val crossOrigin = RtspMessage.Request(
                method = "POST",
                path = "/b/${endpoint.token}/import",
                protocol = "HTTP/1.1",
                headers = mapOf("host" to "192.168.43.1:8888", "origin" to "https://evil.example"),
                body = "{}".toByteArray(),
            )
            assertEquals(403, server.respond(crossOrigin, "192.168.43.10").first)

            val sameOrigin = RtspMessage.Request(
                method = "POST",
                path = "/b/${endpoint.token}/import",
                protocol = "HTTP/1.1",
                headers = mapOf("host" to "192.168.43.1:8888", "origin" to "http://192.168.43.1:8888"),
                body = "{}".toByteArray(),
            )
            assertEquals(200, server.respond(sameOrigin, "192.168.43.10").first)

            assertTrue(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("127.0.0.1")))
            assertTrue(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("192.168.43.10")))
            assertTrue(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("100.64.0.10")))
            assertTrue(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("fd00::1")))
            assertFalse(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("8.8.8.8")))
            assertFalse(SettingsBackupServer.isAllowedClientAddress(InetAddress.getByName("2001:4860:4860::8888")))
        } finally {
            server.stop()
        }
    }
}
