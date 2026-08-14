package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketizerTest {

    @Test
    fun frameDipecahMenjadiChunkDanSeqNaik() {
        val p = Packetizer()
        val data = ByteArray(Protocol.MAX_CHUNK * 2 + 10)
        val packets = p.packetsFor(data, timestampMs = 111u)
        assertEquals(3, packets.size)
        for (chunk in packets) {
            val header = PacketHeader.from(chunk)
            assertTrue(header != null)
            assertTrue(chunk.size <= Protocol.MAX_UDP_PACKET)
        }
        assertEquals(0, PacketHeader.from(packets[0])!!.seq.toInt())
        assertEquals(1, PacketHeader.from(packets[1])!!.seq.toInt())
        assertEquals(2, PacketHeader.from(packets[2])!!.seq.toInt())
    }

    @Test
    fun hanyaChunkPertamaYangMenandaiFrameStart() {
        val p = Packetizer()
        val packets = p.packetsFor(ByteArray(Protocol.MAX_CHUNK + 1), timestampMs = 222u)
        assertTrue(PacketHeader.from(packets[0])!!.isFrameStart)
        assertFalse(PacketHeader.from(packets[1])!!.isFrameStart)
        assertEquals(222u, PacketHeader.from(packets[0])!!.timestampMs)
        assertEquals(222u, PacketHeader.from(packets[1])!!.timestampMs)
    }

    @Test
    fun chunkTerakhirLebihKecilDariMaksimum() {
        val p = Packetizer()
        val packets = p.packetsFor(ByteArray(Protocol.MAX_CHUNK + 5), timestampMs = 0u)
        assertEquals(Protocol.MAX_CHUNK, PacketHeader.from(packets[0])!!.payloadSize.toInt())
        assertEquals(5, PacketHeader.from(packets[1])!!.payloadSize.toInt())
    }
}
