package com.tasirin.cast.protocol

import java.nio.ByteBuffer

/**
 * Header paket UDP bersama (12 byte, big-endian):
 *
 * ```
 * 0      1      2      3      4      5      6      7      8      9     10     11
 * magic  magic  versi  flags  seq_H  seq_L  ts_0   ts_1   ts_2   ts_3   sizeH  sizeL
 * ```
 *
 * - `magic` — 2 byte "TC" ([Protocol.MAGIC]).
 * - `versi` — 1 byte [Protocol.VERSION].
 * - `flags` — bit0 = awal frame ([Protocol.FLAG_FRAME_START]).
 * - `seq` — 2 byte, urutan paket (wrap-around UShort).
 * - `timestampMs` — 4 byte, waktu encode frame (ms, monotonik).
 * - `payloadSize` — 2 byte, ukuran payload setelah header.
 */
data class PacketHeader(
    val seq: UShort,
    val isFrameStart: Boolean,
    val timestampMs: UInt,
    val payloadSize: UShort,
    val isCodecConfig: Boolean = false,
) {
    fun toByteArray(): ByteArray = ByteBuffer.allocate(Protocol.HEADER_SIZE).apply {
        putShort(Protocol.MAGIC)
        put(Protocol.VERSION.toByte())
        put(flags().toByte())
        putShort(seq.toShort())
        putInt(timestampMs.toInt())
        putShort(payloadSize.toShort())
    }.array()

    private fun flags(): Int {
        var f = 0
        if (isFrameStart) f = f or Protocol.FLAG_FRAME_START
        if (isCodecConfig) f = f or Protocol.FLAG_CODEC_CONFIG
        return f
    }

    companion object {
        /** Dekode header dari awal [bytes]. Mengembalikan null jika format tidak valid. */
        fun from(bytes: ByteArray, offset: Int = 0): PacketHeader? {
            if (bytes.size - offset < Protocol.HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes, offset, Protocol.HEADER_SIZE)
            if (buf.short != Protocol.MAGIC) return null
            if (buf.get().toInt() != Protocol.VERSION) return null
            val flags = buf.get().toInt()
            val seq = buf.short.toUShort()
            val timestampMs = buf.int.toUInt()
            val payloadSize = buf.short.toUShort()
            return PacketHeader(
                seq = seq,
                isFrameStart = flags and Protocol.FLAG_FRAME_START != 0,
                timestampMs = timestampMs,
                payloadSize = payloadSize,
                isCodecConfig = flags and Protocol.FLAG_CODEC_CONFIG != 0,
            )
        }
    }
}
