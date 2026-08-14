package com.tasirin.cast.protocol

import java.nio.ByteBuffer

/**
 * Header paket UDP bersama (8 byte, big-endian):
 *
 * ```
 * 0      1      2      3      4      5      6      7
 * magic  magic  versi  flags  seq_H  seq_L  size_H size_L
 * ```
 *
 * - `magic` — 2 byte "TC" ([Protocol.MAGIC]).
 * - `versi` — 1 byte [Protocol.VERSION].
 * - `flags` — bit0 = keyframe ([Protocol.FLAG_KEYFRAME]).
 * - `seq` — 2 byte, urutan paket (wrap-around UShort).
 * - `payloadSize` — 2 byte, ukuran payload setelah header.
 */
data class PacketHeader(
    val seq: UShort,
    val isKeyframe: Boolean,
    val payloadSize: UShort,
) {
    fun toByteArray(): ByteArray = ByteBuffer.allocate(Protocol.HEADER_SIZE).apply {
        putShort(Protocol.MAGIC)
        put(Protocol.VERSION)
        put(if (isKeyframe) Protocol.FLAG_KEYFRAME else 0)
        putShort(seq.toShort())
        putShort(payloadSize.toShort())
    }.array()

    companion object {
        /** Dekode header dari awal [bytes]. Mengembalikan null jika format tidak valid. */
        fun from(bytes: ByteArray, offset: Int = 0): PacketHeader? {
            if (bytes.size - offset < Protocol.HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes, offset, Protocol.HEADER_SIZE)
            if (buf.short != Protocol.MAGIC) return null
            if (buf.get().toInt() != Protocol.VERSION) return null
            val flags = buf.get().toInt()
            val seq = buf.short.toUShort()
            val payloadSize = buf.short.toUShort()
            return PacketHeader(seq, flags and Protocol.FLAG_KEYFRAME != 0, payloadSize)
        }
    }
}
