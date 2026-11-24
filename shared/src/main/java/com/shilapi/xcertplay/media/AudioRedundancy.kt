package com.shilapi.xcertplay.media

/**
 * RFC 2198 redundant audio, which AirPlay advertises as `supportsRTPPacketRedundancy`.
 *
 * The payload starts with one or more 4-byte block headers:
 *
 *     F(1) | block PT(7) | timestamp offset(14) | block length(10)
 *
 * The final block (F=0) is the current frame. Earlier blocks repeat recent frames so a receiver
 * can recover a packet lost on Wi-Fi without waiting for a retransmission. The blocks are data
 * for the same RTP packet, so this code only unpacks the payload; the caller keeps the original
 * 12-byte RTP header (the timestamp in that header is still the current frame's timestamp).
 *
 * Detection is deliberately conservative. If anything does not decode exactly, including a
 * normal raw AAC/Opus access unit, the caller keeps the original payload. This avoids breaking
 * streams whose sender did not actually enable redundancy despite the capability flag.
 */
internal object AudioRedundancy {
    data class Frame(val sampleOffset: Int, val payload: ByteArray)

    /** Returns null when [payload] is not a valid RFC 2198 packet with at least one redundant block. */
    fun unpack(payload: ByteArray, payloadType: Int): List<Frame>? {
        if (payload.size < MIN_PACKET_BYTES || payloadType !in 0..127) return null
        var position = 0
        val frames = ArrayList<Frame>(3)
        while (true) {
            if (position + HEADER_BYTES > payload.size) return null
            val first = payload[position].toInt() and 0xff
            val more = first and 0x80 != 0
            val blockType = first and 0x7f
            if (blockType != payloadType) return null
            val second = payload[position + 1].toInt() and 0xff
            val third = payload[position + 2].toInt() and 0xff
            val fourth = payload[position + 3].toInt() and 0xff
            val sampleOffset = ((second shl 6) or (third ushr 2)) and 0x3fff
            val blockBytes = ((third and 0x03) shl 8) or fourth
            position += HEADER_BYTES
            if (blockBytes <= 0 || position + blockBytes > payload.size) return null
            frames.add(Frame(sampleOffset, payload.copyOfRange(position, position + blockBytes)))
            position += blockBytes
            if (!more) break
        }
        if (position != payload.size) return null
        // A normal, non-redundant RTP payload has a single block. Do not reinterpret it.
        if (frames.size < 2) return null
        return frames
    }

    private const val HEADER_BYTES = 4
    private const val MIN_PACKET_BYTES = HEADER_BYTES * 2 + 1
}
