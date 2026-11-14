package com.tasirin.cast.protocol

/** Satu frame video utuh siap masuk decoder. */
data class Frame(
    val isKeyframe: Boolean,
    val timestampMs: UInt,
    val data: ByteArray,
    val isCodecConfig: Boolean = false,
)

/**
 * Perakit frame (sisi receiver): merakit SATU frame pada satu waktu —
 * frame terbaru selalu menang (sesuai tujuan latensi rendah).
 *
 * Chunk ditempatkan menurut offset byte-nya di header, jadi kedatangan
 * tidak urut di dalam satu frame tidak masalah. Bila paket dari frame
 * yang lebih baru datang sementara frame lama belum lengkap, frame lama
 * dibuang dan [onFrameDropped] dipanggil (receiver lalu meminta keyframe
 * ke sender). Paket dari frame lama yang sudah lewat diabaikan.
 */
class FrameAssembler {

    /** Dipanggil saat frame yang belum lengkap dibuang karena frame lebih baru datang. */
    var onFrameDropped: ((isKeyframe: Boolean) -> Unit)? = null

    private var highestSeq = Int.MIN_VALUE
    private var lastCompletedSeq = Int.MIN_VALUE
    private var buffer: ByteArray? = null
    private var receivedChunks: BooleanArray = BooleanArray(0)
    private var receivedBytes = 0
    private var keyframe = false
    private var codecConfig = false
    private var timestamp = 0u

    fun offer(packet: Packet): Frame? {
        val header = packet.header
        if (header.frameSeq < highestSeq) return null  // sisa frame lama — abaikan
        if (header.frameSeq <= lastCompletedSeq) return null  // frame sudah selesai — abaikan
        if (header.frameSeq > highestSeq || buffer == null) {
            if (buffer != null && receivedBytes < (buffer?.size ?: 0)) {
                onFrameDropped?.invoke(keyframe)
            }
            highestSeq = header.frameSeq
            buffer = ByteArray(header.frameLength)
            receivedChunks = BooleanArray(
                (header.frameLength + Protocol.MAX_CHUNK - 1) / Protocol.MAX_CHUNK
            )
            receivedBytes = 0
            keyframe = header.isKeyframe
            codecConfig = header.isCodecConfig
            timestamp = header.timestampMs
        }
        val buf = buffer ?: return null
        if (header.offset + packet.payload.size > buf.size) return null
        val chunkIndex = header.offset / Protocol.MAX_CHUNK
        if (chunkIndex >= receivedChunks.size || receivedChunks[chunkIndex]) return null
        packet.payload.copyInto(buf, header.offset)
        receivedChunks[chunkIndex] = true
        receivedBytes += packet.payload.size
        if (receivedBytes < buf.size) return null
        val frame = Frame(keyframe, timestamp, buf, codecConfig)
        lastCompletedSeq = highestSeq
        buffer = null
        receivedBytes = 0
        return frame
    }

    fun reset() {
        buffer = null
        receivedBytes = 0
    }
}
