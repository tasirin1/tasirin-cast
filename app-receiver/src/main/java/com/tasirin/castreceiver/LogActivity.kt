package com.tasirin.castreceiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityLogBinding
import com.tasirin.cast.protocol.CastLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Halaman log realtime (pola sama LogActivity di tasirin-download-manager). */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private companion object {
        val EXPORT_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val EXPORT_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    private var logAutoScroll = false
    private var logSearch = ""
    private var lastLogKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.logAutoscroll.isChecked = logAutoScroll
        binding.logAutoscroll.setOnCheckedChangeListener { _, checked ->
            logAutoScroll = checked
        }
        binding.logCopy.setOnClickListener {
            val text = CastLog.snapshot().ifEmpty { getString(R.string.log_empty) }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cast log", text))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
        binding.logClear.setOnClickListener {
            CastLog.clear()
            refreshLog()
        }
        binding.logExport.setOnClickListener { exportLogTxt() }
        binding.logSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                logSearch = s?.toString().orEmpty()
                refreshLog()
            }
        })

        refreshLog()
        val pollLog = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                refreshLog()
                binding.log.postDelayed(this, 1000)
            }
        }
        binding.log.postDelayed(pollLog, 1000)
    }

    private fun exportLogTxt() {
        val log = CastLog.snapshot()
        val header = buildString {
            appendLine("=== ${getString(R.string.app_name)} — Realtime Log ===")
            appendLine("Time: ${EXPORT_TIME.format(Date())}")
            appendLine(
                "App version: " + runCatching {
                    val info = packageManager.getPackageInfo(packageName, 0)
                    info.versionName + " (build " + info.versionCode + ")"
                }.getOrDefault("?")
            )
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(if (log.isBlank()) "(No activity yet)\n" else log)
            appendLine()
        }
        val stamp = EXPORT_STAMP.format(Date())
        val fileName = "tasirin-cast-receiver-log-$stamp.txt"
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(header.toByteArray()) }
                }.onFailure { resolver.delete(uri, null, null) }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null) > 0
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (dir == null) return@runCatching false
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
                File(dir, fileName).writeText(header)
                true
            }
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) R.string.log_exported else R.string.log_export_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshLog() {
        val text = CastLog.snapshot().ifEmpty { getString(R.string.log_empty) }
        val lines = text.lines().count { it.isNotBlank() }
        binding.logCount.text = resources.getQuantityString(R.plurals.log_lines, lines, lines)
        // Kunci render = isi log + kata kunci: teks sama tapi kata kunci
        // berubah tetap harus di-highlight ulang.
        val key = text + "\u0000" + logSearch
        if (key == lastLogKey) return
        lastLogKey = key
        val prevScroll = binding.logScroll.scrollY
        binding.log.text = highlightLog(text)
        binding.logScroll.post {
            if (logAutoScroll) {
                binding.logScroll.fullScroll(View.FOCUS_DOWN)
            } else {
                val max = binding.logScroll.getChildAt(0)?.height
                    ?.minus(binding.logScroll.height) ?: 0
                binding.logScroll.scrollTo(0, prevScroll.coerceIn(0, max.coerceAtLeast(0)))
            }
        }
    }

    /** Sorot baris GAGAL/ERROR merah dan kata kunci pencarian kuning. */
    private fun highlightLog(text: String): CharSequence {
        val q = logSearch.trim()
        if (q.isEmpty() && !text.contains("ERROR") && !text.contains("FAILED")) {
            return text
        }
        val sb = SpannableStringBuilder(text)
        if (q.isNotEmpty()) {
            var from = 0
            while (true) {
                val idx = text.indexOf(q, from, ignoreCase = true)
                if (idx < 0) break
                sb.setSpan(
                    BackgroundColorSpan(0xFFFFE082.toInt()),
                    idx, idx + q.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                from = idx + q.length
            }
        }
        var lineStart = 0
        while (lineStart < sb.length) {
            val lineEnd = text.indexOf('\n', lineStart)
            val end = if (lineEnd < 0) sb.length else lineEnd
            val upper = text.substring(lineStart, end).uppercase()
            if (upper.contains("ERROR") || upper.contains("FAILED")) {
                sb.setSpan(
                    ForegroundColorSpan(Color.RED),
                    lineStart, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (lineEnd < 0) break
            lineStart = lineEnd + 1
        }
        return sb
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
