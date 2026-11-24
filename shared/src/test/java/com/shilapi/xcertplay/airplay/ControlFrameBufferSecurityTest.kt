package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ProtocolException

class ControlFrameBufferSecurityTest {
    @Test
    fun reassemblesChunkedRequestsWithMinimalScans() {
        val buf = ControlFrameBuffer()
        val body = ByteArray(1024) { (it and 0xff).toByte() }
        val header = "POST /feedback RTSP/1.0\r\nCSeq: 7\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()
        val full = header + body

        // Feed header + half body first.
        buf.append(full.copyOfRange(0, header.size + 100))
        assertTrue(buf.drain().isEmpty())
        val scansAfterHeader = buf.scans

        // Drip several more chunks before completion; drain() must skip rescanning until body completes.
        buf.append(full.copyOfRange(header.size + 100, header.size + 500))
        assertTrue(buf.drain().isEmpty())
        assertEquals(scansAfterHeader, buf.scans)

        buf.append(full.copyOfRange(header.size + 500, full.size))
        val messages = buf.drain()
        assertEquals(1, messages.size)
        assertEquals("POST", messages[0].method)
        assertEquals("/feedback", messages[0].path)
        assertEquals(body.size, messages[0].body.size)
    }

    @Test
    fun rejectsOversizedContentLengthAndHeader() {
        val hugeBody = "POST /command RTSP/1.0\r\nContent-Length: 99999999\r\n\r\n".toByteArray()
        try {
            RtspMessage.parseMessages(hugeBody)
            fail("Expected ProtocolException for oversized Content-Length")
        } catch (_: ProtocolException) {
            // Expected.
        }

        val hugeHeader = ByteArray(20 * 1024) { 'A'.code.toByte() }
        try {
            RtspMessage.parseMessages(hugeHeader)
            fail("Expected ProtocolException for oversized RTSP header")
        } catch (_: ProtocolException) {
            // Expected.
        }
    }
}
