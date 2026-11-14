package com.tasirin.castreceiver.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.FrameAssembler
import com.tasirin.cast.protocol.JitterBuffer
import com.tasirin.cast.protocol.Packet
import com.tasirin.cast.protocol.PacketHeader
import com.tasirin.cast.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Receiver layar (kerangka v2): UDP -> FrameAssembler (protokol v4) ->
 * antrean frame kecil -> decoder H.264 -> Surface.
 *
 * Perbedaan mendasar dari v1:
 * - Decoder dikonfigurasi DARI frame konfigurasi yang dikirim sender
 *   (SPS/PPS + ukuran asli sebagai csd-0/csd-1 format) — tidak lagi
 *   ditebak 1280x720 tanpa csd, penyebab utama decoder diam di banyak
 *   perangkat.
 * - Perakitan frame memakai nomor frame + offset + panjang total:
 *   paket hilang = frame itu dibuang bersih, lalu receiver meminta
 *   keyframe (dijatah supaya tidak banjir). Tidak ada lagi sambungan
 *   sisa frame yang merusak gambar.
 * - Thread decoder TIDAK PERNAH menunggu input buffer: bila decoder
 *   sibuk, frame dibuang — latensi tidak menumpuk.
 * - Output dirender hanya frame terbaru; sisanya dibuang.
 * - Surface bisa diganti di tengah jalan ([setSurface]) tanpa me-restart
 *   seluruh penerima; semua operasi codec tetap di thread decoder.
 */
class ScreenReceiver(
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onScreenSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private val frames = JitterBuffer(capacity = 3)
    private val assembler = FrameAssembler()

    private var videoSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    @Volatile private var running = false

    @Volatile private var lastSender: InetAddress? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceChanged = false
    @Volatile private var lastKeyframeRequestMs = 0L

    init {
        assembler.onFrameDropped = {
            // Frame tidak lengkap = ada paket hilang; pulih lewat keyframe.
            requestKeyframeThrottled()
        }
    }

    /** Ganti surface tujuan render; null = berhenti menampilkan (decoder dilepas). */
    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        surfaceChanged = true
    }

    fun start() {
        if (running) return
        running = true
        val video = runCatching { DatagramSocket(Protocol.DEFAULT_PORT) }
            .onFailure { CastLog.event("Gagal membuka port video: ${it.message}") }
            .getOrNull()
        videoSocket = video
        val discovery = runCatching { DatagramSocket(Protocol.DISCOVERY_PORT) }
            .onFailure { CastLog.event("Gagal membuka port discovery: ${it.message}") }
            .getOrNull()
        discoverySocket = discovery
        CastLog.event("Mendengar port ${Protocol.DEFAULT_PORT} (video) & ${Protocol.DISCOVERY_PORT} (discovery)")

        // Teruskan tiap baris log ke sender (prefix TCLG) supaya bisa dibaca
        // langsung dari app sender — memudahkan debugging jarak jauh.
        CastLog.forwarder = { line ->
            val sender = lastSender
            if (sender != null) {
                val data = (Protocol.LOG_PREFIX + line).toByteArray(Charsets.UTF_8)
                sendControl(sender, data)
            }
        }

        discovery?.let { ds -> Thread { discoveryLoop(ds) }.apply { isDaemon = true; start() } }
        Thread { decodeLoop() }.apply { isDaemon = true; start() }
        video?.let { vs -> Thread { receiveLoop(vs) }.apply { isDaemon = true; start() } }
    }

    /** Jawab hello sender di port discovery; catat alamatnya untuk kontrol balik. */
    private fun discoveryLoop(ds: DatagramSocket) {
        val buf = ByteArray(64)
        val hello = Protocol.DISCOVERY_HELLO.toByteArray()
        val ack = Protocol.DISCOVERY_ACK.toByteArray()
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                ds.receive(pkt)
                if (buf.copyOf(pkt.length).contentEquals(hello)) {
                    val isNew = lastSender != pkt.address
                    lastSender = pkt.address
                    sendControl(pkt.address, ack)
                    if (isNew) {
                        CastLog.event("Sender ditemukan: ${pkt.address.hostAddress}")
                        onStatus("Sender: ${pkt.address.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                if (running) CastLog.event("Discovery error: ${e.message}")
            }
        }
    }

    /** Terima paket video + kontrol dari sender, rakit menjadi frame utuh. */
    private fun receiveLoop(vs: DatagramSocket) {
        val buf = ByteArray(Protocol.MAX_UDP_PACKET + 64)
        var rejected = 0L
        var framesCompleted = 0L
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                vs.receive(pkt)
                lastSender = pkt.address
                val raw = buf.copyOf(pkt.length)
                // Paket kontrol dari sender: info ukuran layar asli (aspek).
                if (pkt.length == Protocol.SCREEN_INFO_SIZE &&
                    raw[0] == 0x54.toByte() && raw[1] == 0x43.toByte() &&
                    raw[2] == Protocol.SCREEN_INFO_CMD
                ) {
                    val sw = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
                    val sh = ((raw[5].toInt() and 0xFF) shl 8) or (raw[6].toInt() and 0xFF)
                    if (sw > 0 && sh > 0) {
                        onScreenSize(sw, sh)
                        CastLog.event("Layar sender: ${sw}x${sh}")
                    }
                    continue
                }
                if (pkt.length < Protocol.HEADER_SIZE) continue
                val header = PacketHeader.from(raw)
                if (header == null) {
                    // Magic/versi tidak cocok — biasanya app pasangan masih build lama.
                    rejected++
                    if (rejected % 100 == 1L) {
                        CastLog.event("Paket ditolak (magic/versi) dari ${pkt.address.hostAddress} — pastikan kedua app versi sama")
                    }
                    continue
                }
                if (header.payloadSize != pkt.length - Protocol.HEADER_SIZE) continue
                val payload = raw.copyOfRange(Protocol.HEADER_SIZE, pkt.length)
                val frame = assembler.offer(Packet(header, payload))
                if (frame != null) {
                    frames.offer(frame)
                    framesCompleted++
                    if (framesCompleted % 120 == 0L) {
                        CastLog.event("Frame utuh diterima: $framesCompleted")
                    }
                }
            } catch (e: Exception) {
                if (running) CastLog.event("Receive error: ${e.message}")
            }
        }
    }

    /**
     * Thread decoder — satu-satunya yang menyentuh MediaCodec.
     * Mengelola konfigurasi dari frame konfigurasi, surface yang berganti,
     * dan pemulihan saat codec error.
     */
    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var config: Protocol.CodecConfig? = null
        var configDirty = false
        var awaitingKeyframe = true
        var rendered = 0L
        var renderedLogged = 0L
        var droppedBusy = 0L

        fun releaseCodec() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            awaitingKeyframe = true
        }

        try {
            while (running) {
                if (surfaceChanged) {
                    surfaceChanged = false
                    releaseCodec()
                }
                val frame = frames.poll()
                if (frame == null) {
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                try {
                    if (frame.isCodecConfig) {
                        val parsed = Protocol.parseCodecConfig(frame.data)
                        if (parsed != null && !parsed.sameAs(config)) {
                            config = parsed
                            configDirty = true
                            CastLog.event("Konfigurasi codec diterima: ${parsed.width}x${parsed.height}")
                        }
                        continue
                    }
                    if (configDirty) {
                        releaseCodec()
                        configDirty = false
                    }
                    if (codec == null) {
                        val cfg = config
                        val surf = surface
                        if (cfg == null) {
                            // Belum ada konfigurasi; keyframe berikut membawanya juga.
                            requestKeyframeThrottled()
                            continue
                        }
                        if (surf == null) continue  // belum ada tempat tampil — buang diam-diam
                        codec = createDecoder(cfg, surf)
                        if (codec == null) continue
                    }
                    if (awaitingKeyframe) {
                        if (!frame.isKeyframe) {
                            requestKeyframeThrottled()
                            continue
                        }
                        awaitingKeyframe = false
                    }
                    val dec = codec ?: continue
                    val inIdx = dec.dequeueInputBuffer(0)
                    if (inIdx < 0) {
                        // Decoder sibuk — buang frame daripada menumpuk delay.
                        droppedBusy++
                        if (droppedBusy % 60 == 1L) {
                            CastLog.event("Frame dibuang (decoder sibuk): $droppedBusy")
                        }
                        continue
                    }
                    val input = dec.getInputBuffer(inIdx)
                    if (input == null) continue
                    input.clear()
                    input.put(frame.data)
                    dec.queueInputBuffer(
                        inIdx, 0, frame.data.size,
                        frame.timestampMs.toLong() * 1000, 0,
                    )
                    rendered += drainOutput(dec)
                    if (rendered - renderedLogged >= 120) {
                        renderedLogged = rendered
                        CastLog.event("Frame dirender: $rendered")
                    }
                } catch (e: IllegalStateException) {
                    CastLog.event("Decoder error (${e.message}) — dibuat ulang")
                    releaseCodec()
                    requestKeyframeThrottled()
                } catch (e: Exception) {
                    if (running) CastLog.event("Decode error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } finally {
            releaseCodec()
        }
    }

    /** Buat decoder yang dikonfigurasi dari konfigurasi codec sender. */
    private fun createDecoder(cfg: Protocol.CodecConfig, surf: Surface): MediaCodec? {
        return try {
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height,
            ).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg.sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(cfg.pps))
            }
            dec.configure(format, surf, null, 0)
            dec.start()
            CastLog.event("Decoder siap: ${cfg.width}x${cfg.height}")
            onStatus("Streaming dari ${lastSender?.hostAddress ?: "sender"}")
            dec
        } catch (e: Exception) {
            CastLog.event("ERROR buat decoder: ${e.javaClass.simpleName}: ${e.message}")
            onStatus("Decoder error")
            null
        }
    }

    /** Kuras output decoder; render hanya frame terbaru. Mengembalikan jumlah frame dirender (0/1). */
    private fun drainOutput(dec: MediaCodec): Int {
        val info = MediaCodec.BufferInfo()
        var latest = -1
        var rendered = 0
        try {
            while (true) {
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                when {
                    outIdx >= 0 -> {
                        if (latest >= 0) dec.releaseOutputBuffer(latest, false)
                        latest = outIdx
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        val w = cropOr(f, "crop-width", MediaFormat.KEY_WIDTH)
                        val h = cropOr(f, "crop-height", MediaFormat.KEY_HEIGHT)
                        if (w > 0 && h > 0) {
                            CastLog.event("Decoder output: ${w}x${h}")
                            onVideoSize(w, h)
                        }
                    }
                    else -> break
                }
            }
        } finally {
            if (latest >= 0) {
                dec.releaseOutputBuffer(latest, true)
                rendered = 1
            }
        }
        return rendered
    }

    private fun cropOr(format: MediaFormat, cropKey: String, sizeKey: String): Int {
        val crop = runCatching {
            if (format.containsKey(cropKey)) format.getInteger(cropKey) else 0
        }.getOrDefault(0)
        if (crop > 0) return crop
        return runCatching { format.getInteger(sizeKey) }.getOrDefault(0)
    }

    private fun Protocol.CodecConfig.sameAs(other: Protocol.CodecConfig?): Boolean =
        other != null && width == other.width && height == other.height &&
            sps.contentEquals(other.sps) && pps.contentEquals(other.pps)

    private fun requestKeyframeThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestMs < 500) return
        lastKeyframeRequestMs = now
        val sender = lastSender ?: return
        sendControl(sender, byteArrayOf(0x54, 0x43, Protocol.KEYFRAME_REQUEST_CMD))
    }

    private fun sendControl(to: InetAddress, data: ByteArray) {
        runCatching {
            videoSocket?.send(DatagramPacket(data, data.size, to, Protocol.DEFAULT_PORT))
        }
    }

    fun stop() {
        if (!running) return
        running = false
        CastLog.forwarder = null
        runCatching { discoverySocket?.close() }
        runCatching { videoSocket?.close() }
        discoverySocket = null
        videoSocket = null
        frames.clear()
        assembler.reset()
        CastLog.event("Penerima dihentikan")
    }
}
