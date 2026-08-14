package com.tasirin.cast.protocol

/**
 * Buffer urutan paket UDP sederhana di sisi receiver.
 *
 * - Paket yang datang tidak urut dikumpulkan dulu, lalu dipoll berurutan.
 * - Paket duplikat / sudah lewat dari urutan sekarang dibuang.
 * - Kapasitas dibatasi; paket tertua dibuang saat penuh (antrean paket video,
 *   bukan frame utuh — frame assembly ada di lapisan pemakai).
 */
class JitterBuffer(private val capacity: Int = 64) {

    private val packets = LinkedHashMap<UShort, ByteArray>()
    private var expected: UShort? = null

    /** Simpan paket; false jika duplikat / sudah lewat / buffer penuh. */
    fun offer(seq: UShort, payload: ByteArray): Boolean {
        val exp = expected
        if (exp != null && !isAfterOrEqual(seq, exp)) return false
        if (packets.containsKey(seq)) return false
        if (packets.size >= capacity) {
            val oldest = packets.keys.firstOrNull() ?: return false
            packets.remove(oldest)
        }
        packets[seq] = payload
        return true
    }

    /** Ambil paket berikutnya secara berurutan; null jika kosong. */
    fun poll(): ByteArray? {
        val head = packets.keys.firstOrNull() ?: return null
        val payload = packets.remove(head)
        expected = head
        return payload
    }

    val size: Int get() = packets.size

    /** True jika [candidate] sama dengan atau setelah [base] dalam aritmetika wrap-around. */
    private fun isAfterOrEqual(candidate: UShort, base: UShort): Boolean {
        val delta = (candidate - base).toInt() and 0xFFFF
        return delta < 0x8000
    }
}
