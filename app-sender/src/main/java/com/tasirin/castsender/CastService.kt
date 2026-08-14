package com.tasirin.castsender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tasirin.castsender.stream.ScreenStreamer
import com.tasirin.cast.protocol.CastLog
import java.net.InetAddress

/**
 * Foreground service sender: sejak Android 10+ (targetSdk 29+), MediaProjection
 * WAJIB dibuat dari foreground service bertipe `mediaProjection` — kalau tidak,
 * `getMediaProjection`/`createVirtualDisplay` melempar SecurityException.
 *
 * Alur: MainActivity menerima konsen (dialog), lalu meneruskan resultCode +
 * resultData ke service ini via Intent. Service: startForeground (FGS aktif)
 * -> getMediaProjection -> ScreenStreamer (createVirtualDisplay + streaming).
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
            CastLog.event("Foreground service aktif — membuat MediaProjection")

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                CastLog.event("ERROR service: data konsen tidak valid (code=$resultCode)")
                stopSelf()
                return START_NOT_STICKY
            }
            if (streamer != null) {
                CastLog.event("Streaming sudah berjalan — abaikan start ganda")
                return START_NOT_STICKY
            }

            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                pm.getMediaProjection(resultCode, resultData)
            } catch (t: Throwable) {
                CastLog.event("ERROR getMediaProjection: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (projection == null) {
                CastLog.event("Gagal membuat MediaProjection di service")
                stopSelf()
                return START_NOT_STICKY
            }

            val ip = intent?.getStringExtra(EXTRA_TARGET_IP).orEmpty()
            val targetIp = if (ip.isEmpty()) null else runCatching { InetAddress.getByName(ip) }.getOrNull()
            val s = ScreenStreamer(this, projection, targetIp) { msg -> onStatus?.invoke(msg) }
            streamer = s
            if (s.start()) {
                isStreaming = true
                CastLog.event("Streaming berjalan di foreground service")
            } else {
                streamer = null
                stopSelf()
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_IP = "target_ip"

        private const val ACTION_STOP = "com.tasirin.castsender.action.STOP"
        private const val CHANNEL_ID = "cast_streaming"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isStreaming = false

        @Volatile
        var onStatus: ((String) -> Unit)? = null
    }
}
