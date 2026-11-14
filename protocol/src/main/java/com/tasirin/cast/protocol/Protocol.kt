package com.tasirin.cast.protocol

/**
 * Konstanta protokol bersama sender ↔ receiver.
 *
 * Ubah di file ini saja — kedua app (app-sender & app-receiver) wajib
 * memakai versi yang sama. Perubahan format header = naikkan [VERSION].
 *
 * Protokol v4 (penulisan ulang 2026-11-14): setiap paket membawa nomor
 * frame + offset byte + panjang total frame, jadi perakitan frame di
 * receiver bersifat pasti — paket hilang = frame itu dibuang bersih,
 * tidak lagi menyambung sisa frame lain (penyakit utama protokol v3).
 */
object Protocol {
    /** Port UDP default untuk aliran video + kontrol. */
    const val DEFAULT_PORT = 45555

    /** Port UDP untuk discovery (hello/ACK). */
    const val DISCOVERY_PORT = 45556

    /** Magic "TC" (Tasirin Cast) pada 2 byte pertama header. */
    const val MAGIC: Short = 0x5443

    /** Versi protokol — naikkan saat format header berubah. */
    const val VERSION = 4

    /** Flag: frame ini keyframe (IDR) — decoder bisa mulai dari sini. */
    const val FLAG_KEYFRAME = 0x01

    /** Flag: frame ini berisi konfigurasi codec (SPS/PPS + ukuran). */
    const val FLAG_CODEC_CONFIG = 0x02

    /** Ukuran header paket dalam byte. */
    const val HEADER_SIZE = 22

    /** Maksimum paket UDP — di bawah MTU 1500 agar tidak terfragmentasi. */
    const val MAX_UDP_PACKET = 1200

    /** Payload maksimum per chunk (header sudah dipisah). */
    const val MAX_CHUNK = MAX_UDP_PACKET - HEADER_SIZE

    /** Batas waras panjang satu frame; paket di luar ini dianggap rusak. */
    const val MAX_FRAME_SIZE = 8 * 1024 * 1024

    /** Command kontrol: minta keyframe (paket 3 byte: "TC" + cmd). */
    const val KEYFRAME_REQUEST_CMD: Byte = 0x01

    /** Command kontrol: info ukuran layar sender (paket 7 byte: "TC" + cmd + w + h). */
    const val SCREEN_INFO_CMD: Byte = 0x02

    /** Ukuran paket info layar: 2 magic + 1 cmd + 2 lebar + 2 tinggi. */
    const val SCREEN_INFO_SIZE = 7

    /**
     * Bungkus info ukuran layar asli sender. Dikirim setelah discovery
     * supaya receiver bisa mengoreksi aspek tampilan (potret/lanskap)
     * walau encoder/decoder menjepit resolusi.
     */
    fun screenInfoBytes(width: Int, height: Int): ByteArray = byteArrayOf(
        0x54, 0x43, SCREEN_INFO_CMD,
        ((width shr 8) and 0xFF).toByte(), (width and 0xFF).toByte(),
        ((height shr 8) and 0xFF).toByte(), (height and 0xFF).toByte(),
    )

    /** Isi frame konfigurasi codec: ukuran + SPS/PPS dari format encoder. */
    data class CodecConfig(
        val sps: ByteArray,
        val pps: ByteArray,
        val width: Int,
        val height: Int,
    )

    /**
     * Bungkus konfigurasi codec menjadi payload satu frame konfigurasi:
     * `[w:2][h:2][spsLen:2][sps][ppsLen:2][pps]` (big-endian).
     */
    fun codecConfigBytes(config: CodecConfig): ByteArray {
        val out = ByteArray(6 + config.sps.size + config.pps.size)
        fun putShort(at: Int, value: Int) {
            out[at] = ((value shr 8) and 0xFF).toByte()
            out[at + 1] = (value and 0xFF).toByte()
        }
        putShort(0, config.width)
        putShort(2, config.height)
        putShort(4, config.sps.size)
        config.sps.copyInto(out, 6)
        putShort(6 + config.sps.size, config.pps.size)
        config.pps.copyInto(out, 8 + config.sps.size)
        return out
    }

    /** Urai payload frame konfigurasi; null bila formatnya tidak valid. */
    fun parseCodecConfig(data: ByteArray): CodecConfig? {
        if (data.size < 6) return null
        fun getShort(at: Int): Int =
            ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        val width = getShort(0)
        val height = getShort(2)
        val spsLen = getShort(4)
        if (width <= 0 || height <= 0 || spsLen <= 0) return null
        if (data.size < 8 + spsLen) return null
        val ppsLen = getShort(6 + spsLen)
        if (ppsLen <= 0 || data.size < 8 + spsLen + ppsLen) return null
        return CodecConfig(
            sps = data.copyOfRange(6, 6 + spsLen),
            pps = data.copyOfRange(8 + spsLen, 8 + spsLen + ppsLen),
            width = width,
            height = height,
        )
    }

    /** Pesan discovery. */
    const val DISCOVERY_HELLO = "TC-HI"
    const val DISCOVERY_ACK = "TC-OK"

    /** Awalan paket log receiver yang diteruskan ke sender (untuk debugging). */
    const val LOG_PREFIX = "TCLG"
}
