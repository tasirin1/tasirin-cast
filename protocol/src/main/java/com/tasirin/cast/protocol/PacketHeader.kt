package com.tasirin.cast.protocol

import java.nio.ByteBuffer

/**
 * Header paket UDP bersama (22 byte, big-endian):
 *
 * ```
 *  0-1   magic "TC"
 *  2     versi protokol
 *  3     flags (bit0 keyframe, bit1 codec config)
 *  4-7   frameSeq    — nomor urut FRAME (naik per frame, bukan per paket)
 *  8-11  offset      — posisi byte chunk ini di dalam frame
 * 12-15  frameLength — panjang total frame dalam byte
 * 16-19  timestampMs — waktu encode frame (ms, monotonik, 32 bit rendah)
 * 20-21  payloadSize — ukuran payload sesudah header
 * ```
 *
 * Karena tiap paket tahu persis frame-nya dan posisinya, receiver bisa
 * merakit frame walau paket datang tidak urut, dan membuang frame yang
 * paketnya hilang tanpa merusak frame lain.
 */
data class PacketHeader(
    val frameSeq: Int,
    val offset: Int,
    val frameLength: Int,
    val timestampMs: UInt,
    val payloadSize: Int,
    val isKeyframe: Boolean = false,
    val isCodecConfig: Boolean = false,
) {
    fun toByteArray(): ByteArray = ByteBuffer.allocate(Protocol.HEADER_SIZE).apply {
        putShort(Protocol.MAGIC)
        put(Protocol.VERSION.toByte())
        put(flags().toByte())
        putInt(frameSeq)
        putInt(offset)
        putInt(frameLength)
        putInt(timestampMs.toInt())
        putShort(payloadSize.toShort())
    }.array()

    private fun flags(): Int {
        var f = 0
        if (isKeyframe) f = f or Protocol.FLAG_KEYFRAME
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
            val frameSeq = buf.int
            val chunkOffset = buf.int
            val frameLength = buf.int
            val timestampMs = buf.int.toUInt()
            val payloadSize = buf.short.toInt() and 0xFFFF
            if (frameLength <= 0 || frameLength > Protocol.MAX_FRAME_SIZE) return null
            if (chunkOffset < 0 || chunkOffset >= frameLength) return null
            if (payloadSize <= 0 || payloadSize > Protocol.MAX_CHUNK) return null
            return PacketHeader(
                frameSeq = frameSeq,
                offset = chunkOffset,
                frameLength = frameLength,
                timestampMs = timestampMs,
                payloadSize = payloadSize,
                isKeyframe = flags and Protocol.FLAG_KEYFRAME != 0,
                isCodecConfig = flags and Protocol.FLAG_CODEC_CONFIG != 0,
            )
        }
    }
}
