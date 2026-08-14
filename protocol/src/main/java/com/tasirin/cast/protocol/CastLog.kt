package com.tasirin.cast.protocol

/**
 * Log realtime aplikasi cast — satu instance per proses app (sender & receiver
 * masing-masing punya buffer sendiri). Aman dipanggil dari thread mana pun.
 */
object CastLog {
    private val buffer = RealtimeLog()

    /** Catat satu baris kejadian (stempel waktu otomatis). */
    fun event(message: String) = buffer.append(message)

    /** Snapshot seluruh log untuk ditampilkan / diekspor. */
    fun snapshot(): String = buffer.snapshot()

    fun clear() = buffer.clear()
}
