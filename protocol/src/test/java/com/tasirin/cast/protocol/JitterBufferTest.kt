package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {

    @Test
    fun urutanBiasaDipollBerurutan() {
        val buf = JitterBuffer()
        buf.offer(1u, byteArrayOf(1))
        buf.offer(2u, byteArrayOf(2))
        buf.offer(3u, byteArrayOf(3))
        assertEquals(1, buf.poll()!![0].toInt())
        assertEquals(2, buf.poll()!![0].toInt())
        assertEquals(3, buf.poll()!![0].toInt())
        assertNull(buf.poll())
    }

    @Test
    fun paketTidakUrutDikumpulkanLaluDipoll() {
        val buf = JitterBuffer()
        buf.offer(5u, byteArrayOf(5))
        buf.offer(4u, byteArrayOf(4))
        assertEquals(4, buf.poll()!![0].toInt())
        assertEquals(5, buf.poll()!![0].toInt())
    }

    @Test
    fun duplikatDitolak() {
        val buf = JitterBuffer()
        buf.offer(7u, byteArrayOf(7))
        assertEquals(false, buf.offer(7u, byteArrayOf(8)))
        assertEquals(7, buf.poll()!![0].toInt())
    }

    @Test
    fun paketKetinggalanSetelahPollDibuang() {
        val buf = JitterBuffer()
        buf.offer(2u, byteArrayOf(2))
        buf.poll()
        assertEquals(false, buf.offer(1u, byteArrayOf(1)))
        assertNull(buf.poll())
    }

    @Test
    fun wrapAroundSeqTerusBerjalan() {
        val buf = JitterBuffer()
        buf.offer(65534u, byteArrayOf(1))
        buf.offer(65535u, byteArrayOf(2))
        buf.offer(0u, byteArrayOf(3))
        buf.poll()
        buf.poll()
        assertEquals(3, buf.poll()!![0].toInt())
    }
}
