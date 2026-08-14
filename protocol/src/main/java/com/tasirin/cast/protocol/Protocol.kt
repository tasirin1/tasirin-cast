package com.tasirin.cast.protocol

/**
 * Konstanta protokol bersama sender ↔ receiver.
 *
 * Ubah di file ini saja — kedua app (app-sender & app-receiver) wajib
 * memakai versi yang sama. Perubahan format header = naikkan [VERSION].
 */
object Protocol {
    /** Port UDP default untuk aliran video. */
    const val DEFAULT_PORT = 45555

    /** Magic "TC" (Tasirin Cast) pada 2 byte pertama header. */
    const val MAGIC: Short = 0x5443

    /** Versi protokol — naikkan saat format header berubah. */
    const val VERSION = 1

    /** Flag keyframe (IDR) pada byte flags header. */
    const val FLAG_KEYFRAME = 0x01

    /** Ukuran header paket dalam byte. */
    const val HEADER_SIZE = 8

    /** Maksimum paket UDP — di bawah MTU 1500 agar tidak terfragmentasi. */
    const val MAX_UDP_PACKET = 1200
}
