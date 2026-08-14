package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun packet(seq: Int, isFrameStart: Boolean, payload: ByteArray) = Packet(
    PacketHeader(seq.toUShort(), isFrameStart, 999u, payload.size.toUShort()),
    payload,
)

class FrameAssemblerTest {

    @Test
    fun frameSatuChunkLangsungJadiFrame() {
        val a = FrameAssembler()
        val frame = a.offer(packet(1, isFrameStart = true, payload = byteArrayOf(1, 2, 3)))
        assertEquals(3, frame!!.data.size)
        assertEquals(999u, frame.timestampMs)
        // Setelah reset, frame-start satu chunk langsung jadi frame baru.
        val second = a.offer(packet(2, isFrameStart = true, payload = byteArrayOf(9)))
        assertEquals(1, second!!.data.size)
    }

    @Test
    fun frameMultiChunkDirangkaiSampaiChunkTerakhir() {
        val a = FrameAssembler()
        val full = ByteArray(Protocol.MAX_CHUNK) { 1 }
        val last = ByteArray(7) { 2 }
        assertNull(a.offer(packet(1, isFrameStart = true, payload = full)))
        val frame = a.offer(packet(2, isFrameStart = false, payload = last))
        assertEquals(Protocol.MAX_CHUNK + 7, frame!!.data.size)
        assertEquals(1, frame.data[0].toInt())
        assertEquals(2, frame.data[Protocol.MAX_CHUNK].toInt())
        assertEquals(999u, frame.timestampMs)
    }

    @Test
    fun frameStartBaruMembuangFrameTerpotong() {
        val a = FrameAssembler()
        a.offer(packet(1, isFrameStart = true, payload = ByteArray(Protocol.MAX_CHUNK) { 1 }))
        // Frame lama hilang (terpotong); frame-start baru memulai frame baru.
        val frame = a.offer(packet(5, isFrameStart = true, payload = ByteArray(3) { 5 }))
        assertEquals(3, frame!!.data.size)
        assertEquals(5, frame.data[0].toInt())
        assertEquals(999u, frame.timestampMs)
    }
}
