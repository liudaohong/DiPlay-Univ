package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanPauseSessionTest {
    @Test
    fun pausesAndRestoresSafelyWithJournalRecovery() {
        var journal = false
        var calls = mutableListOf<Boolean>()
        val session = WifiScanPauseSession(
            setEnabled = { enabled ->
                calls.add(enabled)
                WifiScanSwitchResult.DONE
            },
            loadJournal = { journal },
            saveJournal = { v -> journal = v; true },
        )

        val owner = Any()
        val outcome = session.acquire(owner) { true }
        assertTrue(outcome.paused)
        assertTrue(journal)
        assertEquals(listOf(false), calls)

        assertTrue(session.release(owner))
        assertFalse(journal)
        assertEquals(listOf(false, true), calls)
    }

    @Test
    fun dropsJournalWhenAdbIsUnavailable() {
        var journal = false
        val session = WifiScanPauseSession(
            setEnabled = { WifiScanSwitchResult.ADB_OFF },
            loadJournal = { journal },
            saveJournal = { v -> journal = v; true },
        )
        val outcome = session.acquire(Any()) { true }
        assertFalse(outcome.paused)
        assertEquals("adb-off", outcome.reason)
        assertFalse(journal)
    }
}
