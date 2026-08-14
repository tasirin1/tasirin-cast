package com.tasirin.castsender.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.view.Surface
import android.os.Bundle
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Packetizer
import com.tasirin.cast.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue

/**
 * Transmitter layar: MediaProjection -> VirtualDisplay -> encoder H.264 ->
 * packetizer UDP -> receiver. Juga menerima kontrol (ACK discovery & minta
 * keyframe) pada port yang sama.
 */
class ScreenStreamer(
    context: Context,
    private val projection: MediaProjection,
    private val targetIp: InetAddress?,
    private val onStatus: (String) -> Unit,
) {
    private val width = 1280
    private val height = 720
    private val dpi = context.resources.displayMetrics.densityDpi
    private val packetizer = Packetizer()

    private val sendQueue = LinkedBlockingQueue<ByteArray>(512)
    private val targetQueue = LinkedBlockingQueue<InetAddress>()

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
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

        // Thread kontrol: terima ACK discovery & permintaan keyframe.
        Thread {
            val buf = ByteArray(64)
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
            CastLog.event("Streaming ke ${target.hostAddress}:${Protocol.DEFAULT_PORT} ($width x $height)")
            onStatus("Streaming ke ${target.hostAddress}")

            // Drain sinkron: lebih kompatibel lintas perangkat daripada
            // MediaCodec.Callback (callback butuh Looper & rawan bug OEM).
            val info = MediaCodec.BufferInfo()
            var framesSent = 0L
            while (running) {
                when (val out = enc.dequeueOutputBuffer(info, 100_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> {
                        if (out >= 0) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                enc.releaseOutputBuffer(out, false)
                            } else {
                                val buffer = enc.getOutputBuffer(out)
                                if (buffer != null) {
                                    val bytes = ByteArray(info.size)
                                    buffer.position(info.offset)
                                    buffer.get(bytes)
                                    val timestampMs = (info.presentationTimeUs / 1000).toUInt()
                                    for (packet in packetizer.packetsFor(bytes, timestampMs)) {
                                        sendQueue.put(packet)
                                    }
                                    framesSent++
                                    if (framesSent % 30 == 0L) {
                                        CastLog.event("Frame terkirim: $framesSent")
                                    }
                                }
                                enc.releaseOutputBuffer(out, false)
                            }
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

    /** Buat encoder H.264 surface; coba mode bitrate CQ -> VBR -> CBR. */
    private fun createEncoder(): MediaCodec? {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (enc == null) {
            CastLog.event("ERROR encoder: perangkat tidak punya encoder H.264")
            return null
        }
        val modes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        )
        for (mode in modes) {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                // Urutan wajib: configure -> createInputSurface -> start.
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = enc.createInputSurface()
                enc.start()
                CastLog.event("Encoder siap (bitrate mode $mode)")
                return enc
            } catch (e: Exception) {
                CastLog.event("Encoder gagal mode $mode: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { enc.stop() }
                runCatching { enc.release() }
            }
        }
        CastLog.event("ERROR encoder: semua mode bitrate gagal")
        onStatus("Gagal: encoder H.264 tidak tersedia")
        return null
    }

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
        runCatching { projection.stop() }
        sendQueue.clear()
        targetQueue.clear()
        CastLog.event("Streaming dihentikan")
    }
}
