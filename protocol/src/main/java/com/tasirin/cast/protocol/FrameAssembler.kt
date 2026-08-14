package com.tasirin.cast.protocol

/** Satu frame video utuh siap masuk decoder. */
data class Frame(
    val isKeyframe: Boolean,
    val timestampMs: UInt,
    val data: ByteArray,
)

/**
 * Perakit frame (sisi receiver): menerima paket berurutan (hasil JitterBuffer),
 * menggabungkan chunk menjadi frame utuh.
 *
 * Frame selesai saat chunk terakhir < [Protocol.MAX_CHUNK], atau saat paket
 * frame-start baru datang (frame sebelumnya terpotong — dibuang).
 */
class FrameAssembler {

    private val chunks = ArrayList<ByteArray>()
    private var chunksSize = 0
    private var keyframe = false
    private var timestamp = 0u

    fun offer(packet: Packet): Frame? {
        val header = packet.header
        if (header.isFrameStart && chunks.isNotEmpty()) {
            reset()  // frame lama terpotong oleh loss — mulai frame baru
        }
        if (chunks.isEmpty()) {
            keyframe = header.isFrameStart
            timestamp = header.timestampMs
        }
        chunks.add(packet.payload)
        chunksSize += packet.payload.size
        if (packet.payload.size < Protocol.MAX_CHUNK) {
            val data = ByteArray(chunksSize).also { out ->
                var pos = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, pos)
                    pos += chunk.size
                }
            }
            reset()
            return Frame(keyframe, timestamp, data)
        }
        return null
    }

    fun reset() {
        chunks.clear()
        chunksSize = 0
    }
}
