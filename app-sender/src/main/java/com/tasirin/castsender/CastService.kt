package com.tasirin.castsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tasirin.castsender.stream.ScreenStreamer
import com.tasirin.cast.protocol.CastLog
import java.net.InetAddress

/**
 * Foreground service sender: wajib sejak Android 14 (targetSdk 34+) sebelum
 * MediaProjection.createVirtualDisplay dipanggil. MediaProjection dibuat di
 * MainActivity (konsen dialog masih segar, tanpa melewati parcel Intent) lalu
 * diserahkan lewat [pendingProjection]; service menghidupkan FGS + streamer.
 */
class CastService : Service() {

    private var streamer: ScreenStreamer? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground()
            CastLog.event("Foreground service aktif — mengambil projection dari activity")
            val projection = pendingProjection
            pendingProjection = null
            if (projection == null) {
                CastLog.event("ERROR service: projection kosong — streaming dibatalkan")
                stopSelf()
                return START_NOT_STICKY
            }
            if (streamer == null) {
                val ip = intent?.getStringExtra(EXTRA_TARGET_IP).orEmpty()
                val targetIp = if (ip.isEmpty()) null else runCatching { InetAddress.getByName(ip) }.getOrNull()
                val s = ScreenStreamer(this, projection, targetIp) { msg -> onStatus?.invoke(msg) }
                streamer = s
                s.start()
                isStreaming = true
                CastLog.event("Streaming berjalan di foreground service")
            } else {
                CastLog.event("Streaming sudah berjalan — projection cadangan dihentikan")
                runCatching { projection.stop() }
            }
        } catch (t: Throwable) {
            CastLog.event("ERROR service: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        streamer?.stop()
        streamer = null
        isStreaming = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            CastLog.event("ERROR startForeground: ${it.message}")
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_TARGET_IP = "target_ip"

        private const val ACTION_STOP = "com.tasirin.castsender.action.STOP"
        private const val CHANNEL_ID = "cast_streaming"
        private const val NOTIFICATION_ID = 1001

        /** MediaProjection dari dialog konsen — diset MainActivity sebelum start service. */
        @Volatile
        var pendingProjection: MediaProjection? = null

        @Volatile
        var isStreaming = false

        @Volatile
        var onStatus: ((String) -> Unit)? = null
    }
}
