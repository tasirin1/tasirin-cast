package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketizerTest {

    @Test
    fun frameDipecahPerChunkDenganNomorFrameSama() {
        val p = Packetizer()
        val data = ByteArray(Protocol.MAX_CHUNK * 2 + 10)
        val packets = p.packetsFor(data, timestampMs = 111u)
        assertEquals(3, packets.size)
        val headers = packets.map { PacketHeader.from(it)!! }
        // Semua chunk satu frame: nomor frame & panjang total sama, offset naik.
        assertTrue(headers.all { it.frameSeq == headers[0].frameSeq })
        assertTrue(headers.all { it.frameLength == data.size })
        assertEquals(0, headers[0].offset)
        assertEquals(Protocol.MAX_CHUNK, headers[1].offset)
        assertEquals(Protocol.MAX_CHUNK * 2, headers[2].offset)
        for (chunk in packets) assertTrue(chunk.size <= Protocol.MAX_UDP_PACKET)
    }

    @Test
    fun nomorFrameNaikPerFrame() {
        val p = Packetizer()
        val first = p.packetsFor(ByteArray(10), timestampMs = 1u)
        val second = p.packetsFor(ByteArray(10), timestampMs = 2u)
        assertEquals(
            PacketHeader.from(first[0])!!.frameSeq + 1,
            PacketHeader.from(second[0])!!.frameSeq,
        )
    }

    @Test
    fun flagKeyframeDanConfigSampaiKeHeader() {
        val p = Packetizer()
        val key = p.packetsFor(ByteArray(64), timestampMs = 1u, isKeyframe = true)
        assertTrue(PacketHeader.from(key[0])!!.isKeyframe)
        assertFalse(PacketHeader.from(key[0])!!.isCodecConfig)
        val cfg = p.packetsFor(ByteArray(64), timestampMs = 1u, isCodecConfig = true)
        assertTrue(PacketHeader.from(cfg[0])!!.isCodecConfig)
    }

    @Test
    fun chunkTerakhirLebihKecilDariMaksimum() {
        val p = Packetizer()
        val packets = p.packetsFor(ByteArray(Protocol.MAX_CHUNK + 5), timestampMs = 0u)
        assertEquals(Protocol.MAX_CHUNK, PacketHeader.from(packets[0])!!.payloadSize)
        assertEquals(5, PacketHeader.from(packets[1])!!.payloadSize)
    }

    @Test
    fun bufferKosongTidakMenghasilkanPaket() {
        val p = Packetizer()
        assertTrue(p.packetsFor(ByteArray(0), timestampMs = 0u).isEmpty())
    }

    @Test
    fun potonganBisaDirakitKembaliPersis() {
        val p = Packetizer()
        val data = ByteArray(5000) { (it % 251).toByte() }
        val packets = p.packetsFor(data, timestampMs = 42u)
        val out = ByteArray(data.size)
        for (packet in packets) {
            val header = PacketHeader.from(packet)!!
            packet.copyOfRange(Protocol.HEADER_SIZE, packet.size)
                .copyInto(out, header.offset)
        }
        assertTrue(data.contentEquals(out))
    }
}
