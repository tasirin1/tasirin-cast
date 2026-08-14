package com.tasirin.cast.protocol

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Buffer log realtime: aman multi-thread, baris terpotong, daftar dibatasi
 *  supaya snapshot tidak boros memori (pola sama ServerLog di tasirin-download-manager). */
class RealtimeLog(
    private val maxLines: Int = 300,
    private val maxLineLength: Int = 400,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    // Formatter dipakai hanya di dalam synchronized(lock) -> aman dipakai bersama.
    private val stampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Catat satu baris; mengembalikan baris lengkap (stempel + pesan). */
    fun append(message: String): String {
        return synchronized(lock) {
            val stamp = stampFormat.format(Date())
            val line = if (message.length <= maxLineLength) {
                "$stamp $message"
            } else {
                "$stamp ${message.take(maxLineLength)}…"
            }
            buffer.addLast(line)
            while (buffer.size > maxLines) buffer.removeFirst()
            line
        }
    }

    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }

    fun clear() = synchronized(lock) { buffer.clear() }
}
