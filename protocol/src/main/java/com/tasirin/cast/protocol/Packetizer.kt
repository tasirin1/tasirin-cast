package com.tasirin.cast.protocol

/**
 * Pemecah frame encoder menjadi paket UDP (sisi sender).
 *
 * Satu panggilan = satu frame. Tiap paket membawa nomor frame yang sama
 * dengan offset byte-nya masing-masing (lihat [PacketHeader]), jadi
 * receiver tidak bergantung pada urutan kedatangan paket.
 */
class Packetizer {
    private var nextFrameSeq = 0

    fun packetsFor(
        buffer: ByteArray,
        timestampMs: UInt,
        isKeyframe: Boolean = false,
        isCodecConfig: Boolean = false,
    ): List<ByteArray> {
        if (buffer.isEmpty()) return emptyList()
        val frameSeq = nextFrameSeq++
        val out = ArrayList<ByteArray>((buffer.size + Protocol.MAX_CHUNK - 1) / Protocol.MAX_CHUNK)
        var offset = 0
        while (offset < buffer.size) {
            val len = minOf(Protocol.MAX_CHUNK, buffer.size - offset)
            val header = PacketHeader(
                frameSeq = frameSeq,
                offset = offset,
                frameLength = buffer.size,
                timestampMs = timestampMs,
                payloadSize = len,
                isKeyframe = isKeyframe,
                isCodecConfig = isCodecConfig,
            )
            out.add(header.toByteArray() + buffer.copyOfRange(offset, offset + len))
            offset += len
        }
        return out
    }
}
