package com.tasirin.cast.protocol

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/** Buffer log realtime: aman multi-thread, baris terpotong, daftar dibatasi
 *  supaya snapshot tidak boros memori (pola sama ServerLog di tasirin-download-manager).
 *
 *  Penguncian memakai ReentrantLock, BUKAN blok synchronized: sesudah
 *  dioptimalkan R8, blok synchronized yang menempel di dalam Runnable
 *  thread pernah ditolak verifier ART ("expected to be within a
 *  catch-all for an instruction where a monitor is held" — VerifyError
 *  saat tombol Mulai ditekan, build 100034). ReentrantLock tidak memakai
 *  instruksi monitor sama sekali, jadi kelas bug itu hilang. */
class RealtimeLog(
    private val maxLines: Int = 300,
    private val maxLineLength: Int = 400,
) {
    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<String>()
    // Formatter dipakai hanya selagi lock dipegang -> aman dipakai bersama.
    private val stampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Catat satu baris; mengembalikan baris lengkap (stempel + pesan). */
    fun append(message: String): String {
        lock.lock()
        try {
            val stamp = stampFormat.format(Date())
            val line = if (message.length <= maxLineLength) {
                "$stamp $message"
            } else {
                "$stamp ${message.take(maxLineLength)}…"
            }
            buffer.addLast(line)
            while (buffer.size > maxLines) buffer.removeFirst()
            return line
        } finally {
            lock.unlock()
        }
    }

    fun snapshot(): String {
        lock.lock()
        try {
            return buffer.joinToString("\n")
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            buffer.clear()
        } finally {
            lock.unlock()
        }
    }
}
