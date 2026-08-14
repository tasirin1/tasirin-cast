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
    const val VERSION = 2

    /** Flag awal frame (frame start) pada byte flags header. */
    const val FLAG_FRAME_START = 0x01

    /** Ukuran header paket dalam byte. */
    const val HEADER_SIZE = 12

    /** Maksimum paket UDP — di bawah MTU 1500 agar tidak terfragmentasi. */
    const val MAX_UDP_PACKET = 1200

    /** Payload maksimum per chunk (header sudah dipisah). */
    const val MAX_CHUNK = MAX_UDP_PACKET - HEADER_SIZE

    /** Command kontrol: minta keyframe (paket 3 byte: "TC" + cmd). */
    const val KEYFRAME_REQUEST_CMD: Byte = 0x01

    /** Pesan discovery. */
    const val DISCOVERY_HELLO = "TC-HI"
    const val DISCOVERY_ACK = "TC-OK"
}
