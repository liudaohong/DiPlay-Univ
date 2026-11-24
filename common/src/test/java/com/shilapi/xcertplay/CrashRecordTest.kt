package com.shilapi.xcertplay

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecordTest {
    @Test
    fun formatsStackAndPreservesDiPlayFrames() {
        val error = IllegalStateException("test boom")
        val text = CrashRecord.format("main", error, 1_700_000_000_000L)
        assertTrue(text.contains("crash atEpochMs=1700000000000 thread=main"))
        assertTrue(text.contains("IllegalStateException: test boom"))
        assertTrue(text.contains("com.shilapi.xcertplay.CrashRecordTest"))
    }
}
