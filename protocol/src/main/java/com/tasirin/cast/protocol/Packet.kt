package com.tasirin.cast.protocol

/** Satu paket UDP hasil dekode header (urutan belum dijamin). */
data class Packet(
    val header: PacketHeader,
    val payload: ByteArray,
)
