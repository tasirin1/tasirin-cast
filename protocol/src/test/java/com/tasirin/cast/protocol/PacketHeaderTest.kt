package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketHeaderTest {

    @Test
    fun roundTripMembacaKembaliSemuaField() {
        val header = PacketHeader(seq = 1234u, isFrameStart = true, timestampMs = 98765u, payloadSize = 900u)
        val decoded = PacketHeader.from(header.toByteArray())
        assertEquals(header, decoded)
    }

    @Test
    fun magicSalahDitolak() {
        val bytes = byteArrayOf(0, 0, Protocol.VERSION.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun versiSalahDitolak() {
        val bytes = byteArrayOf(
            (Protocol.MAGIC.toInt() ushr 8).toByte(),
            Protocol.MAGIC.toByte(),
            99,
            0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertNull(PacketHeader.from(bytes))
    }

    @Test
    fun panjangKurangDitolak() {
        assertNull(PacketHeader.from(ByteArray(4)))
    }
}
