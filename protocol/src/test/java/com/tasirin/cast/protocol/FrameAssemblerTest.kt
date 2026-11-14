package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chunk(
    frameSeq: Int,
    offset: Int,
    frameLength: Int,
    payload: ByteArray,
    isKeyframe: Boolean = false,
) = Packet(
    PacketHeader(
        frameSeq = frameSeq,
        offset = offset,
        frameLength = frameLength,
        timestampMs = 999u,
        payloadSize = payload.size,
        isKeyframe = isKeyframe,
    ),
    payload,
)

class FrameAssemblerTest {

    @Test
    fun frameSatuChunkLangsungJadi() {
        val a = FrameAssembler()
        val frame = a.offer(chunk(0, 0, 3, byteArrayOf(1, 2, 3)))
        assertEquals(3, frame!!.data.size)
        assertEquals(999u, frame.timestampMs)
    }

    @Test
    fun frameMultiChunkDirangkaiMenurutOffset() {
        val a = FrameAssembler()
        val total = Protocol.MAX_CHUNK + 7
        assertNull(a.offer(chunk(0, 0, total, ByteArray(Protocol.MAX_CHUNK) { 1 })))
        val frame = a.offer(chunk(0, Protocol.MAX_CHUNK, total, ByteArray(7) { 2 }))
        assertEquals(total, frame!!.data.size)
        assertEquals(1, frame.data[0].toInt())
        assertEquals(2, frame.data[Protocol.MAX_CHUNK].toInt())
    }

    @Test
    fun chunkDatangTidakUrutTetapLengkap() {
        val a = FrameAssembler()
        val total = Protocol.MAX_CHUNK + 7
        // Chunk terakhir datang lebih dulu.
        assertNull(a.offer(chunk(0, Protocol.MAX_CHUNK, total, ByteArray(7) { 2 })))
        val frame = a.offer(chunk(0, 0, total, ByteArray(Protocol.MAX_CHUNK) { 1 }))
        assertEquals(total, frame!!.data.size)
        assertEquals(1, frame.data[0].toInt())
        assertEquals(2, frame.data[Protocol.MAX_CHUNK].toInt())
    }

    @Test
    fun chunkDuplikatDiabaikan() {
        val a = FrameAssembler()
        val total = Protocol.MAX_CHUNK + 7
        val first = chunk(0, 0, total, ByteArray(Protocol.MAX_CHUNK) { 1 })
        assertNull(a.offer(first))
        assertNull(a.offer(first))
        val frame = a.offer(chunk(0, Protocol.MAX_CHUNK, total, ByteArray(7) { 2 }))
        assertEquals(total, frame!!.data.size)
    }

    @Test
    fun frameBaruMembuangFrameTerpotongDanMelapor() {
        val a = FrameAssembler()
        var dropped = -1
        a.onFrameDropped = { wasKeyframe -> dropped = if (wasKeyframe) 1 else 0 }
        val total = Protocol.MAX_CHUNK + 7
        a.offer(chunk(0, 0, total, ByteArray(Protocol.MAX_CHUNK) { 1 }, isKeyframe = true))
        // Chunk kedua frame 0 hilang; frame 1 mulai -> frame 0 dibuang + dilaporkan.
        val frame = a.offer(chunk(1, 0, 3, byteArrayOf(9, 9, 9)))
        assertEquals(3, frame!!.data.size)
        assertEquals(1, dropped)
    }

    @Test
    fun sisaFrameLamaSesudahFrameBaruDiabaikan() {
        val a = FrameAssembler()
        val total = Protocol.MAX_CHUNK + 7
        a.offer(chunk(0, 0, total, ByteArray(Protocol.MAX_CHUNK) { 1 }))
        val f1 = a.offer(chunk(1, 0, 3, byteArrayOf(7, 7, 7)))
        assertEquals(3, f1!!.data.size)
        // Sisa chunk frame 0 datang terlambat — tidak boleh mengganggu.
        assertNull(a.offer(chunk(0, Protocol.MAX_CHUNK, total, ByteArray(7) { 2 })))
    }

    @Test
    fun flagKeyframeIkutKeFrame() {
        val a = FrameAssembler()
        val frame = a.offer(chunk(0, 0, 3, byteArrayOf(1, 2, 3), isKeyframe = true))
        assertTrue(frame!!.isKeyframe)
    }
}
