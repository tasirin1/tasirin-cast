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
import kotlin.math.min

/**
 * Transmitter layar (kerangka v2): MediaProjection -> VirtualDisplay ->
 * encoder H.264 -> packetizer protokol v4 -> UDP, semua dalam satu alur
 * yang jelas:
 *
 * - Encoder dikonfigurasi dari chain fallback yang sudah terbukti di
 *   banyak perangkat (CBR -> VBR -> CQ -> default).
 * - Konfigurasi codec (SPS/PPS + ukuran) dibaca dari FORMAT encoder —
 *   bukan dari buffer config — lalu dikirim sebagai frame konfigurasi
 *   tersendiri, dan diulang tepat sebelum setiap keyframe. Receiver
 *   mengonfigurasi decoder-nya dari frame itu (decoder tidak pernah
 *   ditebak formatnya).
 * - Thread drain encoder mengirim paket LANGSUNG ke socket, tanpa
 *   antrean perantara: tidak ada penumpukan yang menambah delay.
 * - Kontrol masuk (ACK discovery, minta keyframe, log receiver) dibaca
 *   di thread tersendiri pada socket yang sama.
 */
class ScreenStreamer(
    context: Context,
    private val projection: MediaProjection,
    private val targetIp: InetAddress?,
    private val quality: Quality,
    private val onStatus: (String) -> Unit,
) {
    // Resolusi capture mengikuti aspek layar asli (mode potret = video
    // potret), diskalakan agar sisi terpanjang tidak melebihi preset.
    private val screenW: Int
    private val screenH: Int
    private val width: Int
    private val height: Int
    private val dpi: Int

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

    private val packetizer = Packetizer()

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var socket: DatagramSocket? = null

    @Volatile private var running = false

    /** Alamat receiver; null selama belum ketemu (discovery/manual). */
    @Volatile private var target: InetAddress? = null

    /** Payload frame konfigurasi codec; siap begitu format encoder diketahui. */
    @Volatile private var configPayload: ByteArray? = null

    @Volatile private var lastKeyframeRequestMs = 0L

    // Wajib sejak Android 14: callback MediaProjection didaftarkan SEBELUM
    // createVirtualDisplay (kalau tidak, IllegalStateException).
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            CastLog.event("MediaProjection dihentikan oleh sistem")
            stop()
        }
    }

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

        runCatching {
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }

        val enc = createEncoder()
        if (enc == null) {
            running = false
            runCatching { sock.close() }
            runCatching { projection.stop() }
            return false
        }
        codec = enc

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "TasirinCast",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null,
            )
        } catch (e: Exception) {
            CastLog.event("ERROR createVirtualDisplay: ${e.javaClass.simpleName}: ${e.message}")
            onStatus("Gagal: ${e.message}")
            stop()
            return false
        }

        Thread { controlLoop(sock) }.apply { isDaemon = true; start() }

        if (targetIp != null) {
            onTargetFound(targetIp, manual = true)
        } else {
            CastLog.event("Mencari receiver (broadcast)…")
            onStatus("Mencari receiver…")
            Thread { discoveryLoop(sock) }.apply { isDaemon = true; start() }
        }

        Thread { drainLoop(sock) }.apply { isDaemon = true; start() }
        CastLog.event("Streaming siap — ${width}x${height}, ${quality.bitrate / 1_000_000} Mbps")
        return true
    }

    /** Target terkunci: kirim info layar + konfigurasi + paksa keyframe awal. */
    private fun onTargetFound(address: InetAddress, manual: Boolean) {
        val first = target == null
        target = address
        if (first) {
            CastLog.event("Receiver terkunci: ${address.hostAddress}${if (manual) " (manual)" else ""}")
            onStatus("Streaming ke ${address.hostAddress}")
        }
        val sock = socket ?: return
        runCatching {
            val info = Protocol.screenInfoBytes(screenW, screenH)
            sock.send(DatagramPacket(info, info.size, address, Protocol.DEFAULT_PORT))
        }
        configPayload?.let { sendFrame(sock, address, it, 0u, isCodecConfig = true) }
        requestSyncFrame()
    }

    /** Broadcast hello berkala sampai receiver menjawab. */
    private fun discoveryLoop(sock: DatagramSocket) {
        val hello = Protocol.DISCOVERY_HELLO.toByteArray()
        val packet = DatagramPacket(
            hello, hello.size,
            InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT,
        )
        while (running && target == null) {
            runCatching { sock.send(packet) }
            try {
                Thread.sleep(1500)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /** Baca kontrol masuk: ACK discovery, permintaan keyframe, log receiver. */
    private fun controlLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        val ack = Protocol.DISCOVERY_ACK.toByteArray()
        val logPrefix = Protocol.LOG_PREFIX.toByteArray()
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val data = buf.copyOf(pkt.length)
                when {
                    data.contentEquals(ack) -> {
                        if (targetIp == null) onTargetFound(pkt.address, manual = false)
                    }
                    data.size == 3 && data[0] == 0x54.toByte() && data[1] == 0x43.toByte() &&
                        data[2] == Protocol.KEYFRAME_REQUEST_CMD -> {
                        val now = System.currentTimeMillis()
                        if (now - lastKeyframeRequestMs >= 200) {
                            lastKeyframeRequestMs = now
                            requestSyncFrame()
                        }
                    }
                    data.size > logPrefix.size &&
                        data.copyOfRange(0, logPrefix.size).contentEquals(logPrefix) -> {
                        CastLog.event(
                            "R: " + data.copyOfRange(logPrefix.size, data.size)
                                .toString(Charsets.UTF_8)
                        )
                    }
                }
            } catch (e: Exception) {
                if (running) CastLog.event("Kontrol error: ${e.message}")
            }
        }
    }

    /** Drain encoder -> kirim langsung. Satu-satunya penulis ke socket video. */
    private fun drainLoop(sock: DatagramSocket) {
        val enc = codec ?: return
        val info = MediaCodec.BufferInfo()
        var framesSent = 0L
        var framesDroppedNoTarget = 0L
        try {
            while (running) {
                when (val out = enc.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readCodecConfig(enc)
                    else -> {
                        if (out < 0) continue
                        try {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Buffer config mentah tidak dikirim; konfigurasi
                                // resmi dibaca dari format (readCodecConfig).
                                continue
                            }
                            if (info.size <= 0) continue
                            val buffer = enc.getOutputBuffer(out) ?: continue
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            val timestampMs = (info.presentationTimeUs / 1000).toUInt()
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val dest = target
                            if (dest == null) {
                                framesDroppedNoTarget++
                                continue
                            }
                            if (isKey) {
                                configPayload?.let {
                                    sendFrame(sock, dest, it, timestampMs, isCodecConfig = true)
                                }
                            }
                            sendFrame(sock, dest, bytes, timestampMs, isKeyframe = isKey)
                            framesSent++
                            if (framesSent % 120 == 0L) {
                                CastLog.event("Frame terkirim: $framesSent (tanpa target dibuang: $framesDroppedNoTarget)")
                            }
                        } finally {
                            runCatching { enc.releaseOutputBuffer(out, false) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                CastLog.event("ERROR encoder drain: ${e.javaClass.simpleName}: ${e.message}")
                onStatus("Gagal: ${e.message}")
            }
        }
    }

    /** Baca SPS/PPS + ukuran dari format encoder dan siapkan frame konfigurasi. */
    private fun readCodecConfig(enc: MediaCodec) {
        runCatching {
            val format = enc.outputFormat
            val spsBuf = format.getByteBuffer("csd-0") ?: return
            val ppsBuf = format.getByteBuffer("csd-1") ?: return
            val sps = ByteArray(spsBuf.remaining()).also { spsBuf.get(it) }
            val pps = ByteArray(ppsBuf.remaining()).also { ppsBuf.get(it) }
            configPayload = Protocol.codecConfigBytes(
                Protocol.CodecConfig(sps, pps, width, height)
            )
            CastLog.event("Konfigurasi codec siap: ${width}x${height}, SPS ${sps.size} B, PPS ${pps.size} B")
            target?.let { dest ->
                socket?.let { sendFrame(it, dest, configPayload!!, 0u, isCodecConfig = true) }
            }
        }.onFailure {
            CastLog.event("Baca konfigurasi codec gagal: ${it.message}")
        }
    }

    private fun sendFrame(
        sock: DatagramSocket,
        dest: InetAddress,
        data: ByteArray,
        timestampMs: UInt,
        isKeyframe: Boolean = false,
        isCodecConfig: Boolean = false,
    ) {
        for (packet in packetizer.packetsFor(data, timestampMs, isKeyframe, isCodecConfig)) {
            runCatching { sock.send(DatagramPacket(packet, packet.size, dest, Protocol.DEFAULT_PORT)) }
        }
    }

    /** Buat encoder H.264 surface; coba beberapa konfigurasi sampai berhasil. */
    private fun createEncoder(): MediaCodec? {
        // Mode bitrate paling kompatibel di depan; CQ (constant quality)
        // sering ditolak sebagian perangkat. Codec BARU tiap percobaan —
        // codec yang sudah di-release tidak bisa dipakai ulang.
        val variants = listOf(
            Variant("CBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true),
            Variant("VBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, true),
            Variant("CQ", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, true),
            Variant("default", null, true),
            Variant("default tanpa max-b-frames", null, false),
        )
        for (v in variants) {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
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

    private fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        }
    }

    fun stop() {
        if (!running && codec == null) return
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        target = null
        configPayload = null
        CastLog.event("Streaming dihentikan")
    }
}
