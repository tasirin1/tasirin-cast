package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun header(seq: Int, payloadSize: Int = 1) =
    PacketHeader(seq.toUShort(), false, 0u, payloadSize.toUShort())

class JitterBufferTest {

    @Test
    fun urutanBiasaDipollBerurutan() {
        val buf = JitterBuffer()
        buf.offer(header(1), byteArrayOf(1))
        buf.offer(header(2), byteArrayOf(2))
        buf.offer(header(3), byteArrayOf(3))
        assertEquals(1, buf.poll()!!.payload[0].toInt())
        assertEquals(2, buf.poll()!!.payload[0].toInt())
        assertEquals(3, buf.poll()!!.payload[0].toInt())
        assertNull(buf.poll())
    }

    @Test
    fun paketTidakUrutDikumpulkanLaluDipoll() {
        val buf = JitterBuffer()
        buf.offer(header(5), byteArrayOf(5))
        buf.offer(header(4), byteArrayOf(4))
        assertEquals(4, buf.poll()!!.payload[0].toInt())
        assertEquals(5, buf.poll()!!.payload[0].toInt())
    }

    @Test
    fun duplikatDitolak() {
        val buf = JitterBuffer()
        buf.offer(header(7), byteArrayOf(7))
        assertEquals(false, buf.offer(header(7), byteArrayOf(8)))
        assertEquals(7, buf.poll()!!.payload[0].toInt())
    }

    @Test
    fun paketKetinggalanSetelahPollDibuang() {
        val buf = JitterBuffer()
        buf.offer(header(2), byteArrayOf(2))
        buf.poll()
        assertEquals(false, buf.offer(header(1), byteArrayOf(1)))
        assertNull(buf.poll())
    }

    @Test
    fun wrapAroundSeqTerusBerjalan() {
        val buf = JitterBuffer()
        buf.offer(header(65534), byteArrayOf(1))
        buf.offer(header(65535), byteArrayOf(2))
        buf.poll()
        buf.poll()
        // Seq 0 datang setelah 65535 (wrap-around UShort) — harus diterima & dipoll.
        buf.offer(header(0), byteArrayOf(3))
        assertEquals(3, buf.poll()!!.payload[0].toInt())
    }
}
