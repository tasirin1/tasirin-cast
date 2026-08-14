package com.tasirin.castreceiver.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.FrameAssembler
import com.tasirin.cast.protocol.JitterBuffer
import com.tasirin.cast.protocol.PacketHeader
import com.tasirin.cast.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Receiver layar: UDP (video + discovery) -> JitterBuffer -> FrameAssembler ->
 * decoder H.264 -> SurfaceView. Mendeteksi paket hilang lalu meminta keyframe
 * ke sender.
 */
class ScreenReceiver(
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private val frameQueue = LinkedBlockingQueue<com.tasirin.cast.protocol.Frame>(32)
    private val jitter = JitterBuffer()
    private val assembler = FrameAssembler()

    private var videoSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    @Volatile private var framesReceived = 0L

    private var lastPolledSeq: UShort? = null
    private var lastSender: InetAddress? = null

    fun start() {
        running = true
        val video = runCatching { DatagramSocket(Protocol.DEFAULT_PORT) }.getOrNull()
        videoSocket = video
        val discovery = runCatching { DatagramSocket(Protocol.DISCOVERY_PORT) }.getOrNull()
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

        discovery?.let { ds ->
            Thread {
                val buf = ByteArray(64)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        ds.receive(pkt)
                        val msg = buf.copyOf(pkt.length).toString(Charsets.US_ASCII)
                        if (msg == Protocol.DISCOVERY_HELLO) {
                            lastSender = pkt.address
                            sendControl(pkt.address, Protocol.DISCOVERY_ACK.toByteArray())
                            CastLog.event("Sender ditemukan: ${pkt.address.hostAddress}")
                            onStatus("Sender: ${pkt.address.hostAddress}")
                        }
                    } catch (e: Exception) {
                        if (running) CastLog.event("Discovery error: ${e.message}")
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        Thread { decodeLoop() }.apply { isDaemon = true; start() }

        video?.let { vs ->
            Thread {
                val buf = ByteArray(Protocol.MAX_UDP_PACKET)
                var rejected = 0L
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        vs.receive(pkt)
                        lastSender = pkt.address
                        if (pkt.length < Protocol.HEADER_SIZE) continue
                        val header = PacketHeader.from(buf)
                        if (header == null) {
                            // Magic/versi tidak cocok — biasanya receiver masih build lama.
                            rejected++
                            if (rejected % 100 == 1L) {
                                CastLog.event("Paket ditolak (magic/versi) dari ${pkt.address.hostAddress} — pastikan receiver build terbaru")
                            }
                            continue
                        }
                        val payload = buf.copyOfRange(Protocol.HEADER_SIZE, pkt.length)
                        if (jitter.offer(header, payload)) {
                            var packet = jitter.poll()
                            while (packet != null) {
                                checkGap(packet.header, vs)
                                val frame = assembler.offer(packet)
                                if (frame != null) {
                                    frameQueue.offer(frame)
                                    framesReceived++
                                    if (framesReceived % 30 == 0L) {
                                        CastLog.event("Frame diterima: $framesReceived")
                                    }
                                }
                                packet = jitter.poll()
                            }
                        }
                    } catch (e: Exception) {
                        if (running) CastLog.event("Receive error: ${e.message}")
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private fun decodeLoop() {
        try {
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            dec.configure(format, surface, null, 0)
            dec.start()
            codec = dec
            CastLog.event("Decoder siap — menunggu frame")

            // Drain output (render) di thread terpisah.
            Thread {
                val info = MediaCodec.BufferInfo()
                var rendered = 0L
                while (running) {
                    try {
                        val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx >= 0) {
                            dec.releaseOutputBuffer(outIdx, true)
                            rendered++
                            if (rendered % 30 == 0L) {
                                CastLog.event("Frame dirender: $rendered")
                            }
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = dec.outputFormat
                            val w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                            val h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                            CastLog.event("Decoder output: ${w}x${h}")
                            if (w > 0 && h > 0) onVideoSize(w, h)
                        }
                    } catch (e: Exception) {
                        if (running) CastLog.event("Render error: ${e.message}")
                    }
                }
            }.apply { isDaemon = true; start() }

            var fed = 0L
            var dropped = 0L
            while (running) {
                val frame = frameQueue.poll(1, TimeUnit.SECONDS) ?: continue
                // Tunggu input buffer tersedia (jangan buang frame seperti dulu).
                var inIdx = -1
                for (attempt in 0 until 20) {
                    inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) break
                }
                if (inIdx < 0) {
                    dropped++
                    if (dropped % 30 == 1L) CastLog.event("Frame dibuang (input penuh): $dropped")
                    continue
                }
                val input = dec.getInputBuffer(inIdx) ?: continue
                input.clear()
                input.put(frame.data)
                val flags = if (frame.isCodecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                dec.queueInputBuffer(inIdx, 0, frame.data.size, frame.timestampMs.toLong() * 1000, flags)
                fed++
                if (fed % 30 == 0L) {
                    CastLog.event("Frame masuk decoder: $fed")
                }
            }
        } catch (e: Exception) {
            CastLog.event("ERROR decoder: ${e.message}")
            onStatus("Decoder error")
        }
    }

    private fun checkGap(header: PacketHeader, video: DatagramSocket) {
        val last = lastPolledSeq
        if (last != null) {
            val delta = (header.seq - last).toInt() and 0xFFFF
            if (delta > 1 && delta < 0x8000) {
                assembler.reset()
                requestKeyframe(video)
                CastLog.event("Paket hilang (gap $delta) — minta keyframe")
            }
        }
        lastPolledSeq = header.seq
    }

    private fun requestKeyframe(video: DatagramSocket) {
        val sender = lastSender ?: return
        sendControl(sender, byteArrayOf(0x54, 0x43, Protocol.KEYFRAME_REQUEST_CMD))
    }

    private fun sendControl(to: InetAddress, data: ByteArray) {
        runCatching {
            videoSocket?.send(DatagramPacket(data, data.size, to, Protocol.DEFAULT_PORT))
        }
    }

    fun stop() {
        running = false
        CastLog.forwarder = null
        runCatching { discoverySocket?.close() }
        runCatching { videoSocket?.close() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        frameQueue.clear()
        CastLog.event("Penerima dihentikan")
    }
}
