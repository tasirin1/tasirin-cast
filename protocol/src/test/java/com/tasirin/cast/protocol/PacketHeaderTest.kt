package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketHeaderTest {

    @Test
    fun roundTripMembacaKembaliSemuaField() {
        val header = PacketHeader(
            frameSeq = 1234,
            offset = 1178,
            frameLength = 5000,
            timestampMs = 98765u,
            payloadSize = 900,
            isKeyframe = true,
        )
        val decoded = PacketHeader.from(header.toByteArray())
        assertEquals(header, decoded)
    }

    @Test
    fun roundTripFlagCodecConfig() {
        val header = PacketHeader(
            frameSeq = 7,
            offset = 0,
            frameLength = 40,
            timestampMs = 1u,
            payloadSize = 40,
            isCodecConfig = true,
        )
        val decoded = PacketHeader.from(header.toByteArray())
        assertEquals(true, decoded!!.isCodecConfig)
        assertEquals(false, decoded.isKeyframe)
        assertEquals(header, decoded)
    }

    @Test
    fun magicSalahDitolak() {
        val bytes = ByteArray(Protocol.HEADER_SIZE)
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun versiSalahDitolak() {
        val header = PacketHeader(1, 0, 10, 0u, 10)
        val bytes = header.toByteArray()
        bytes[2] = 99
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun panjangFrameTidakWarasDitolak() {
        val header = PacketHeader(1, 0, 10, 0u, 10)
        val bytes = header.toByteArray()
        // frameLength = 0 (byte 12..15 big-endian)
        bytes[12] = 0; bytes[13] = 0; bytes[14] = 0; bytes[15] = 0
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun offsetDiLuarFrameDitolak() {
        val header = PacketHeader(1, 0, 10, 0u, 10)
        val bytes = header.toByteArray()
        // offset = 999 (byte 8..11 big-endian) melebihi frameLength 10
        bytes[10] = 0x03; bytes[11] = 0xE7.toByte()
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun panjangKurangDitolak() {
        assertNull(PacketHeader.from(ByteArray(4)))
    }
}
