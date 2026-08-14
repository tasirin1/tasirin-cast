package com.tasirin.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeLogTest {

    @Test
    fun appendMenambahStempelWaktuDanPesan() {
        val log = RealtimeLog()
        log.append("hello")
        assertTrue(log.snapshot().matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} hello")))
    }

    @Test
    fun barisPanjangDipotong() {
        val log = RealtimeLog(maxLineLength = 10)
        log.append("123456789012345")
        val line = log.snapshot()
        assertTrue(line.endsWith("1234567890…"))
    }

    @Test
    fun maxLinesMembuangBarisTerlama() {
        val log = RealtimeLog(maxLines = 2)
        log.append("baris1")
        log.append("baris2")
        log.append("baris3")
        val snapshot = log.snapshot()
        assertEquals(2, snapshot.lines().size)
        assertTrue(snapshot.contains("baris2"))
        assertTrue(snapshot.contains("baris3"))
        assertTrue(!snapshot.contains("baris1"))
    }

    @Test
    fun clearMengosongkanBuffer() {
        val log = RealtimeLog()
        log.append("hello")
        log.clear()
        assertEquals("", log.snapshot())
    }
}
