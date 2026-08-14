package com.tasirin.castsender.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Packetizer
import com.tasirin.cast.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min

/**
 * Transmitter layar: MediaProjection -> VirtualDisplay -> encoder H.264 ->
 * packetizer UDP -> receiver. Juga menerima kontrol (ACK discovery & minta
 * keyframe) pada port yang sama.
 */
class ScreenStreamer(
    context: Context,
    private val projection: MediaProjection,
    private val targetIp: InetAddress?,
    private val quality: Quality,
    private val onStatus: (String) -> Unit,
) {
    // Resolusi capture mengikuti aspek layar asli (mode potret = video
    // potret, tidak lagi diregangkan jadi 16:9), diskalakan agar sisi
    // terpanjang tidak melebihi preset kualitas.
    private val screenW: Int
    private val screenH: Int
    private val width: Int
    private val height: Int
    private val dpi: Int
    private val packetizer = Packetizer()

    init {
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val longSide = maxOf(screenW, screenH)
        val scale = min(1f, quality.maxDimension.toFloat() / longSide)
        // Genapkan ukuran (kelipatan 2) supaya aman untuk encoder.
        width = ((screenW * scale).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        height = ((screenH * scale).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        dpi = dm.densityDpi
        CastLog.event("Resolusi capture: ${width}x${height} (layar ${screenW}x${screenH}, preset ${quality.key})")
    }

    private val sendQueue = LinkedBlockingQueue<ByteArray>(512)
    private val targetQueue = LinkedBlockingQueue<InetAddress>()

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Wajib sejak Android 14: callback MediaProjection didaftarkan SEBELUM
    // createVirtualDisplay (kalau tidak, IllegalStateException).
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            CastLog.event("MediaProjection dihentikan oleh sistem")
            stop()
        }
    }
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var started = false
    private var sendThread: Thread? = null

    fun start(): Boolean {
        running = true
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.DEFAULT_PORT))
                broadcast = true
            }
        } catch (e: Exception) {
            running = false
            CastLog.event("Gagal membuka port ${Protocol.DEFAULT_PORT}: ${e.message}")
            onStatus("Gagal: ${e.message}")
            runCatching { projection.stop() }
            return false
        }
        socket = sock
        CastLog.event("Port ${Protocol.DEFAULT_PORT} dibuka — menunggu target")
        runCatching {
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }

        // Thread kontrol: terima ACK discovery, log receiver & permintaan keyframe.
        Thread {
            val buf = ByteArray(600)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    when {
                        data.size == 5 && data.contentEquals(Protocol.DISCOVERY_ACK.toByteArray()) -> {
                            CastLog.event("Receiver ditemukan: ${pkt.address.hostAddress}")
                            targetQueue.offer(pkt.address)
                        }
                        data.size == 3 && data[2] == Protocol.KEYFRAME_REQUEST_CMD -> {
                            requestSyncFrame()
                            CastLog.event("Permintaan keyframe diterima")
                        }
                        data.size > Protocol.LOG_PREFIX.length &&
                            data.copyOfRange(0, Protocol.LOG_PREFIX.length)
                                .contentEquals(Protocol.LOG_PREFIX.toByteArray()) -> {
                            // Baris log dari receiver — tampilkan dengan prefix "R:".
                            CastLog.event(
                                "R: " + data.copyOfRange(Protocol.LOG_PREFIX.length, data.size)
                                    .toString(Charsets.UTF_8)
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (running) CastLog.event("Kontrol error: ${e.message}")
                }
            }
        }.apply { isDaemon = true; start() }

        // Auto-discovery: broadcast hello sampai target diketahui.
        if (targetIp != null) {
            targetQueue.offer(targetIp)
        } else {
            CastLog.event("Mencari receiver (broadcast)…")
            onStatus("Mencari receiver…")
            Thread {
                val hello = Protocol.DISCOVERY_HELLO.toByteArray()
                val broadcast = DatagramPacket(
                    hello, hello.size,
                    InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT
                )
                while (running && !started) {
                    try { sock.send(broadcast) } catch (e: Exception) {
                        if (running) CastLog.event("Broadcast error: ${e.message}")
                    }
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
        }

        // Thread pengirim: tunggu target, lalu jalankan encoder + kirim.
        sendThread = Thread {
            val target = runCatching { targetQueue.take() }.getOrNull() ?: return@Thread
            if (!running) return@Thread
            // Info ukuran layar dikirim lebih dulu supaya receiver bisa
            // mengoreksi aspek tampilan (potret/lanskap).
            runCatching {
                val info = Protocol.screenInfoBytes(screenW, screenH)
                sock.send(DatagramPacket(info, info.size, target, Protocol.DEFAULT_PORT))
                CastLog.event("Info layar ${screenW}x${screenH} dikirim ke receiver")
            }
            startCodecAndSend(target, sock)
        }.apply { isDaemon = true; start() }
        return true
    }

    private fun startCodecAndSend(target: InetAddress, sock: DatagramSocket) {
        try {
            CastLog.event("Menyiapkan encoder H.264 untuk ${target.hostAddress}")
            val enc = createEncoder() ?: return
            codec = enc

            virtualDisplay = projection.createVirtualDisplay(
                "TasirinCast",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface!!, null, null
            )
            started = true
            CastLog.event("Streaming ke ${target.hostAddress}:${Protocol.DEFAULT_PORT} ($width x $height, ${quality.bitrate / 1_000_000} Mbps)")
            onStatus("Streaming ke ${target.hostAddress}")

            // Drain sinkron: lebih kompatibel lintas perangkat daripada
            // MediaCodec.Callback (callback butuh Looper & rawan bug OEM).
            val info = MediaCodec.BufferInfo()
            var framesSent = 0L
            var configBytes: ByteArray? = null
            while (running) {
                when (val out = enc.dequeueOutputBuffer(info, 100_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> {
                        if (out >= 0) {
                            val buffer = enc.getOutputBuffer(out)
                            if (buffer != null) {
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(bytes)
                                val timestampMs = (info.presentationTimeUs / 1000).toUInt()
                                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (isConfig) {
                                    configBytes = bytes
                                    // SPS/PPS dikirim sendiri dulu (receiver bisa pakai csd-0),
                                    // lalu ditempel juga ke keyframe berikutnya (in-band) —
                                    // decoder yang mengabaikan buffer config tetap bisa mulai.
                                    for (packet in packetizer.packetsFor(bytes, timestampMs, isCodecConfig = true)) {
                                        sendQueue.put(packet)
                                    }
                                } else {
                                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    val payload = if (isKey && configBytes != null) configBytes!! + bytes else bytes
                                    for (packet in packetizer.packetsFor(payload, timestampMs)) {
                                        sendQueue.put(packet)
                                    }
                                    framesSent++
                                    if (framesSent % 30 == 0L) {
                                        CastLog.event("Frame terkirim: $framesSent")
                                    }
                                }
                            }
                            enc.releaseOutputBuffer(out, false)
                        }
                    }
                }
                sendPending(sock, target)
            }
        } catch (e: Exception) {
            CastLog.event("ERROR encoder: ${e.javaClass.simpleName}: ${e.message}")
            onStatus("Gagal: ${e.message}")
        } finally {
            stop()
        }
    }

    /** Buat encoder H.264 surface; coba beberapa konfigurasi sampai berhasil. */
    private fun createEncoder(): MediaCodec? {
        // Mode bitrate paling kompatibel di depan; CQ (constant quality) sering
        // ditolak sebagian perangkat.
        val variants = listOf(
            Variant("CBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true),
            Variant("VBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, true),
            Variant("CQ", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, true),
            Variant("default", null, true),
            Variant("default tanpa max-b-frames", null, false),
        )
        for (v in variants) {
            // Codec BARU tiap percobaan — codec yang sudah di-release tidak bisa dipakai ulang.
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (enc == null) {
                CastLog.event("ERROR encoder: perangkat tidak punya encoder H.264")
                return null
            }
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
                    v.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (v.maxBFrames) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                // Urutan wajib: configure -> createInputSurface -> start.
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = enc.createInputSurface()
                enc.start()
                CastLog.event("Encoder siap (${v.label})")
                return enc
            } catch (e: Exception) {
                CastLog.event("Encoder gagal ${v.label} (${e.javaClass.simpleName}): ${e.message}")
                runCatching { enc.stop() }
                runCatching { enc.release() }
            }
        }
        CastLog.event("ERROR encoder: semua konfigurasi gagal")
        onStatus("Gagal: encoder H.264 tidak tersedia")
        return null
    }

    /** Satu kandidat konfigurasi encoder. */
    private data class Variant(
        val label: String,
        val bitrateMode: Int?,
        val maxBFrames: Boolean,
    )

    /** Kirim semua paket yang sudah menunggu di antrean. */
    private fun sendPending(sock: DatagramSocket, target: InetAddress) {
        while (running) {
            val packet = sendQueue.poll() ?: break
            sock.send(DatagramPacket(packet, packet.size, target, Protocol.DEFAULT_PORT))
        }
    }

    private fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        }
    }

    fun stop() {
        running = false
        started = false
        runCatching { socket?.close() }
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        sendQueue.clear()
        targetQueue.clear()
        CastLog.event("Streaming dihentikan")
    }
}
