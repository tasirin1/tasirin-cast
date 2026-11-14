package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun frame(tag: Int) = Frame(
    isKeyframe = false,
    timestampMs = tag.toUInt(),
    data = byteArrayOf(tag.toByte()),
)

class JitterBufferTest {

    @Test
    fun frameDipollSesuaiUrutanMasuk() {
        val buf = JitterBuffer()
        buf.offer(frame(1))
        buf.offer(frame(2))
        buf.offer(frame(3))
        assertEquals(1, buf.poll()!!.data[0].toInt())
        assertEquals(2, buf.poll()!!.data[0].toInt())
        assertEquals(3, buf.poll()!!.data[0].toInt())
        assertNull(buf.poll())
    }

    @Test
    fun penuhMembuangFrameTertua() {
        val buf = JitterBuffer(capacity = 3)
        buf.offer(frame(1))
        buf.offer(frame(2))
        buf.offer(frame(3))
        assertTrue(buf.offer(frame(4)))
        assertEquals(3, buf.size)
        assertEquals(2, buf.poll()!!.data[0].toInt())
        assertEquals(3, buf.poll()!!.data[0].toInt())
        assertEquals(4, buf.poll()!!.data[0].toInt())
    }

    @Test
    fun clearMengosongkanAntrean() {
        val buf = JitterBuffer()
        buf.offer(frame(1))
        buf.clear()
        assertEquals(0, buf.size)
        assertNull(buf.poll())
    }

    @Test
    fun kapasitasNolMenolakFrame() {
        val buf = JitterBuffer(capacity = 0)
        assertEquals(false, buf.offer(frame(1)))
        assertNull(buf.poll())
    }
}
