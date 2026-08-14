package com.tasirin.cast.protocol

/**
 * Pemecah frame encoder menjadi paket UDP (sisi sender).
 *
 * Satu frame dipecah menjadi chunk [Protocol.MAX_CHUNK]; paket pertama
 * menandai awal frame ([PacketHeader.isFrameStart] = true) dan membawa
 * [PacketHeader.timestampMs] frame tersebut. Seq naik per chunk.
 */
class Packetizer {
    private var seq: UShort = 0u

    fun packetsFor(buffer: ByteArray, timestampMs: UInt): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < buffer.size) {
            val len = minOf(Protocol.MAX_CHUNK, buffer.size - offset)
            val header = PacketHeader(
                seq = seq,
                isFrameStart = offset == 0,
                timestampMs = timestampMs,
                payloadSize = len.toUShort(),
            )
            out.add(header.toByteArray() + buffer.copyOfRange(offset, offset + len))
            seq++
            offset += len
        }
        return out
    }
}
