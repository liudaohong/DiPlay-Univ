package com.shilapi.xcertplay.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SettingsBackupServerTest {
    @Test
    fun servesSettingsExportAndDiagnosticReportOverLocalhost() {
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
        try {
            val exportConn = URL("${endpoint!!.url("127.0.0.1")}export").openConnection() as HttpURLConnection
            assertEquals(200, exportConn.responseCode)
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
}
