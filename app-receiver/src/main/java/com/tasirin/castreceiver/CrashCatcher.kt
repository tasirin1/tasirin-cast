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
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
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
