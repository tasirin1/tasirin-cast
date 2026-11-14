package com.tasirin.cast.protocol

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock

/**
 * Antrean frame utuh antara thread jaringan dan thread decoder (receiver).
 *
 * Sengaja kecil: untuk latensi rendah, frame lama yang menumpuk lebih
 * baik dibuang daripada ditampilkan terlambat. Saat penuh, frame TERTUA
 * yang dibuang ([offer] tetap menerima frame baru). Semua metode aman
 * dipanggil dari banyak thread.
 *
 * Penguncian memakai ReentrantLock, BUKAN @Synchronized: metode
 * synchronized yang ditempel R8 ke dalam Runnable thread pernah ditolak
 * verifier ART ("expected to be within a catch-all for an instruction
 * where a monitor is held" — VerifyError saat tombol Mulai ditekan,
 * masih terjadi di build 100035 yang baru mengganti RealtimeLog).
 * ReentrantLock tidak memakai instruksi monitor sama sekali.
 */
class JitterBuffer(private val capacity: Int = 3) {

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Frame>()

    /** Masukkan frame; mengembalikan true bila frame tersimpan. */
    fun offer(frame: Frame): Boolean {
        lock.lock()
        try {
            if (capacity <= 0) return false
            while (queue.size >= capacity) queue.removeFirst()
            queue.addLast(frame)
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Ambil frame tertua; null bila kosong. */
    fun poll(): Frame? {
        lock.lock()
        try {
            return if (queue.isEmpty()) null else queue.removeFirst()
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            queue.clear()
        } finally {
            lock.unlock()
        }
    }

    val size: Int
        get() {
            lock.lock()
            try {
                return queue.size
            } finally {
                lock.unlock()
            }
        }
}
