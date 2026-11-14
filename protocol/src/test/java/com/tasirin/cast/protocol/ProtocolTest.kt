package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun konfigurasiCodecRoundTripUkuranNyataHPPemilik() {
        // Ukuran persis dari log HP pemilik 2026-11-15 (SPS 23 B, PPS 8 B):
        // dulu codecConfigBytes melempar IndexOutOfBoundsException karena
        // buffer kurang 2 byte (lupa ruang field ppsLen).
        val config = Protocol.CodecConfig(
            sps = ByteArray(23) { it.toByte() },
            pps = ByteArray(8) { (100 + it).toByte() },
            width = 288,
            height = 640,
        )
        val bytes = Protocol.codecConfigBytes(config)
        assertEquals(8 + 23 + 8, bytes.size)
        val parsed = Protocol.parseCodecConfig(bytes)
        assertNotNull(parsed)
        assertEquals(288, parsed!!.width)
        assertEquals(640, parsed.height)
        assertTrue(config.sps.contentEquals(parsed.sps))
        assertTrue(config.pps.contentEquals(parsed.pps))
    }

    @Test
    fun konfigurasiCodecRoundTripUkuranLain() {
        val config = Protocol.CodecConfig(
            sps = ByteArray(20) { (it * 3).toByte() },
            pps = ByteArray(4) { (it * 7).toByte() },
            width = 1280,
            height = 720,
        )
        val parsed = Protocol.parseCodecConfig(Protocol.codecConfigBytes(config))
        assertNotNull(parsed)
        assertEquals(1280, parsed!!.width)
        assertEquals(720, parsed.height)
        assertTrue(config.sps.contentEquals(parsed.sps))
        assertTrue(config.pps.contentEquals(parsed.pps))
    }

    @Test
    fun uraiKonfigurasiCodecMenolakDataRusak() {
        assertNull(Protocol.parseCodecConfig(ByteArray(5)))
        assertNull(Protocol.parseCodecConfig(ByteArray(20)))
    }
}
