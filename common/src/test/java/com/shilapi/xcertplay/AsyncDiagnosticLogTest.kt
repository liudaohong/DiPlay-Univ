package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.MediaAudioLines
import com.shilapi.xcertplay.media.MusicVolume
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AsyncDiagnosticLogTest {
    @Test fun queuedEvidenceIsRedactedAndTimestampedBeforeUsingItsCapturedFileTarget() {
        val folder = Files.createTempDirectory("diplay-async-log").toFile()
        val old = SessionLogFile(folder.resolve("old.log"))
        val current = SessionLogFile(folder.resolve("current.log"))
        try {
            old.reset("old session"); current.reset("current session")
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC attempt=1 teardown end elapsedMs=117", 0)
            AsyncDiagnosticLog.append(old, "Microphone: start type=telephony codec=OPUS peer=192.168.49.1", 0)
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC token=private-token", 0)
            AsyncDiagnosticLog.append(old, "Microphone: payload=private-audio", 0)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val evidence = old.file.readText()
            assertTrue(evidence.contains("teardown end elapsedMs=117"))
            assertTrue(evidence.contains("Microphone: start type=telephony codec=OPUS peer=[ip]"))
            assertFalse(evidence.contains("192.168.49.1"))
            assertFalse(evidence.contains("private-token")); assertFalse(evidence.contains("private-audio"))
            assertFalse(current.file.readText().contains("teardown end"))
            assertEquals(4, evidence.lineSequence().count())
        } finally {
            old.close(); current.close(); folder.deleteRecursively()
        }
    }

    @Test fun teardownEvidenceThatArrivesAfterTheLogClosedIsStillWritten() {
        // E.g. a Wi-Fi scan pause released, or a car Bluetooth link handed back, after the CarPlay screen closed.
        val folder = Files.createTempDirectory("diplay-async-late").toFile()
        val log = SessionLogFile(folder.resolve("diplay.log"))
        try {
            log.reset("session")
            log.close()
            AsyncDiagnosticLog.append(
                log,
                "CONNECTION_DIAGNOSTIC attempt=3 Wi-Fi scan pause lease released",
                0,
                afterClose = true,
            )
            AsyncDiagnosticLog.append(log, "Microphone: stop type=telephony", 0)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val evidence = log.file.readText()
            assertTrue(evidence.contains("Wi-Fi scan pause lease released"))
            // Everything else still stops at close.
            assertFalse(evidence.contains("Microphone: stop"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun aLateLineNeverRotatesOrTruncatesTheLogANewerScreenStarted() {
        val folder = Files.createTempDirectory("diplay-async-shared").toFile()
        val path = folder.resolve("diplay.log")
        val old = SessionLogFile(path)
        val current = SessionLogFile(path)
        try {
            old.reset("old session")
            old.close()
            current.reset("current session")
            current.appendNow("current line")
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC attempt=1 teardown end", 0, afterClose = true)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val text = path.readText()
            assertTrue(text.contains("current session"))
            assertTrue(text.contains("current line"))
            assertTrue(text.contains("teardown end"))
        } finally {
            current.close(); folder.deleteRecursively()
        }
    }

    @Test fun mediaTrackStallLinesSurviveRedactorUnchanged() {
        val lines = buildList {
            for (rebuild in listOf(true, false)) {
                add(MediaAudioLines.notConsuming(0L, 3_000_000_000L, 151, 0, rebuild))
                add(MediaAudioLines.notConsuming(0xffff_ffffL, 3_000_000_000L, 5, 192, rebuild))
                add(MediaAudioLines.notConsuming(null, 3_000_000_000L, 0, 192, rebuild))
                add(MediaAudioLines.rebuilt(rebuild))
            }
            add(MediaAudioLines.consumingAgain(960))
            add(MediaAudioLines.started(MusicVolume(3, 39, muted = false, fixed = true), 2, 14))
            add(MediaAudioLines.started(null, null, 0))
        }
        lines.forEach { line ->
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }
}
