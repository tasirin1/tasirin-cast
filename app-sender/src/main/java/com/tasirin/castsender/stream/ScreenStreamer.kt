package com.tasirin.castsender.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Packetizer
import com.tasirin.cast.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private var virtualDisplay: VirtualDisplay? = null
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var started = false
    private var sendThread: Thread? = null

    fun start() {
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
            return
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
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            enc.setCallback(encoderCallback(sock, target))
            enc.start()
            codec = enc

            virtualDisplay = projection.createVirtualDisplay(
                "TasirinCast",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
            started = true
            CastLog.event("Streaming ke ${target.hostAddress}:${Protocol.DEFAULT_PORT} ($width x $height)")
            onStatus("Streaming ke ${target.hostAddress}")

            while (running) {
                val packet = sendQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                sock.send(DatagramPacket(packet, packet.size, target, Protocol.DEFAULT_PORT))
            }
        } catch (e: Exception) {
            CastLog.event("ERROR encoder: ${e.message}")
            onStatus("Gagal: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun encoderCallback(sock: DatagramSocket, target: InetAddress) =
        object : MediaCodec.Callback() {
            private var framesSent = 0L

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }
                    val buffer = codec.getOutputBuffer(index) ?: return
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    codec.releaseOutputBuffer(index, false)

                    val timestampMs = (info.presentationTimeUs / 1000).toUInt()
                    for (packet in packetizer.packetsFor(bytes, timestampMs)) {
                        sendQueue.put(packet)
                    }
                    framesSent++
                    if (framesSent % 30 == 0L) {
                        CastLog.event("Frame terkirim: $framesSent")
                    }
                } catch (t: Throwable) {
                    CastLog.event("ERROR encoder callback: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                CastLog.event("ERROR encoder: ${e.diagnosticInfo ?: e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
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
