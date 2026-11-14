package com.tasirin.cast.protocol

import java.util.ArrayDeque

/**
 * Antrean frame utuh antara thread jaringan dan thread decoder (receiver).
 *
 * Sengaja kecil: untuk latensi rendah, frame lama yang menumpuk lebih
 * baik dibuang daripada ditampilkan terlambat. Saat penuh, frame TERTUA
 * yang dibuang ([offer] tetap menerima frame baru). Semua metode aman
 * dipanggil dari banyak thread.
 */
class JitterBuffer(private val capacity: Int = 3) {

    private val queue = ArrayDeque<Frame>()

    /** Masukkan frame; mengembalikan true bila frame tersimpan. */
    @Synchronized
    fun offer(frame: Frame): Boolean {
        if (capacity <= 0) return false
        while (queue.size >= capacity) queue.removeFirst()
        queue.addLast(frame)
        return true
    }

    /** Ambil frame tertua; null bila kosong. */
    @Synchronized
    fun poll(): Frame? = if (queue.isEmpty()) null else queue.removeFirst()

    @Synchronized
    fun clear() = queue.clear()

    val size: Int
        @Synchronized get() = queue.size
}
