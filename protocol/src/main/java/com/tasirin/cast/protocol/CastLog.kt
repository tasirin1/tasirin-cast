package com.tasirin.cast.protocol

/**
 * Log realtime aplikasi cast — satu instance per proses app (sender & receiver
 * masing-masing punya buffer sendiri). Aman dipanggil dari thread mana pun.
 */
object CastLog {
    private val buffer = RealtimeLog()

    /** Hook opsional (diisi app receiver) untuk meneruskan tiap baris log ke sender via UDP. */
    @Volatile
    var forwarder: ((String) -> Unit)? = null

    /** Catat satu baris kejadian (stempel waktu otomatis). */
    fun event(message: String) {
        val line = buffer.append(message)
        forwarder?.invoke(line)
    }

    /** Snapshot seluruh log untuk ditampilkan / diekspor. */
    fun snapshot(): String = buffer.snapshot()

    fun clear() = buffer.clear()
}
