package com.tasirin.cast.protocol

/**
 * Konstanta protokol bersama sender ↔ receiver.
 *
 * Ubah di file ini saja — kedua app (app-sender & app-receiver) wajib
 * memakai versi yang sama. Perubahan format header = naikkan [VERSION].
 */
object Protocol {
    /** Port UDP default untuk aliran video + kontrol. */
    const val DEFAULT_PORT = 45555

    /** Port UDP untuk discovery (hello/ACK). */
    const val DISCOVERY_PORT = 45556

    /** Magic "TC" (Tasirin Cast) pada 2 byte pertama header. */
    const val MAGIC: Short = 0x5443

    /** Versi protokol — naikkan saat format header berubah. */
    const val VERSION = 3

    /** Flag awal frame (frame start) pada byte flags header. */
    const val FLAG_FRAME_START = 0x01

    /** Flag frame berisi konfigurasi codec (SPS/PPS) sebelum frame IDR pertama. */
    const val FLAG_CODEC_CONFIG = 0x02

    /** Ukuran header paket dalam byte. */
    const val HEADER_SIZE = 12

    /** Maksimum paket UDP — di bawah MTU 1500 agar tidak terfragmentasi. */
    const val MAX_UDP_PACKET = 1200

    /** Payload maksimum per chunk (header sudah dipisah). */
    const val MAX_CHUNK = MAX_UDP_PACKET - HEADER_SIZE

    /** Command kontrol: minta keyframe (paket 3 byte: "TC" + cmd). */
    const val KEYFRAME_REQUEST_CMD: Byte = 0x01

    /** Command kontrol: info ukuran layar sender (paket 7 byte: "TC" + cmd + w + h). */
    const val SCREEN_INFO_CMD: Byte = 0x02

    /** Ukuran paket info layar: 2 magic + 1 cmd + 2 lebar + 2 tinggi. */
    const val SCREEN_INFO_SIZE = 7

    /**
     * Bungkus info ukuran layar asli sender. Dikirim sekali setelah discovery
     * supaya receiver bisa mengoreksi aspek tampilan (potret/lanskap) walau
     * encoder/decoder menjepit resolusi (mis. 720x1600 -> 720x1088).
     */
    fun screenInfoBytes(width: Int, height: Int): ByteArray = byteArrayOf(
        0x54, 0x43, SCREEN_INFO_CMD,
        ((width shr 8) and 0xFF).toByte(), (width and 0xFF).toByte(),
        ((height shr 8) and 0xFF).toByte(), (height and 0xFF).toByte(),
    )

    /** Pesan discovery. */
    const val DISCOVERY_HELLO = "TC-HI"
    const val DISCOVERY_ACK = "TC-OK"

    /** Awalan paket log receiver yang diteruskan ke sender (untuk debugging). */
    const val LOG_PREFIX = "TCLG"
}
