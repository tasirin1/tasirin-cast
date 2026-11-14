package com.tasirin.castreceiver

import android.content.Context
import com.tasirin.cast.protocol.CastLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Menangkap crash tak terduga: stack trace ditulis ke Realtime Log + file
 * `crash_last.txt`, lalu dimuat lagi ke log saat app dibuka berikutnya
 * (lihat [drainToLog]) supaya penyebab force close selalu terlihat.
 */
object CrashCatcher {

    private const val FILE_NAME = "crash_last.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = sw.toString()
                CastLog.event("CRASH [${thread.name}]")
                text.lineSequence().forEach { CastLog.event("  $it") }
                runCatching { File(app.filesDir, FILE_NAME).writeText(text) }
                dumpKeDownloads(app, text)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }


    /**
     * Salin laporan crash ke folder Download (API 29+) supaya tetap bisa
     * dibaca walau app tidak bisa dibuka sama sekali (layar Log tidak
     * terjangkau). Nama tetap CRASH-<segmen paket terakhir>.txt.
     */
    private fun dumpKeDownloads(app: Context, text: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val resolver = app.contentResolver
            val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val col = android.provider.MediaStore.Downloads.DISPLAY_NAME
            val name = "CRASH-" + app.packageName.substringAfterLast('.') + ".txt"
            runCatching { resolver.delete(uri, "$col = ?", arrayOf(name)) }
            val values = android.content.ContentValues().apply {
                put(col, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val out = resolver.insert(uri, values) ?: return
            resolver.openOutputStream(out)?.use { it.write(text.toByteArray()) }
            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(out, done, null, null)
        }
    }

    /** Muat laporan crash (jika ada) ke Realtime Log lalu hapus file. */
    fun drainToLog(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            CastLog.event("Laporan crash dari sesi sebelumnya:")
            file.readText().lineSequence().forEach { CastLog.event("  $it") }
            file.delete()
        } catch (_: Throwable) {
        }
    }
}
