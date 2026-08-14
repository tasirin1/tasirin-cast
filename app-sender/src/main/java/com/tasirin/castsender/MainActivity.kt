package com.tasirin.castsender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tasirin.castsender.databinding.ActivityMainBinding
import com.tasirin.castsender.stream.Quality
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == RESULT_OK && result.data != null) {
                    val ip = binding.etIp.text.toString().trim()
                    val targetIp = if (ip.isEmpty()) {
                        null
                    } else {
                        runCatching { InetAddress.getByName(ip) }.getOrNull()
                    }
                    if (ip.isNotEmpty() && targetIp == null) {
                        CastLog.event("IP receiver tidak valid: $ip")
                        Toast.makeText(this, R.string.toast_invalid_ip, Toast.LENGTH_SHORT).show()
                    } else {
                        // Konsen dikirim ke service; MediaProjection dibuat DI SERVICE
                        // setelah foreground service aktif (wajib sejak Android 10+).
                        val quality = Quality.values()[binding.spinQuality.selectedItemPosition]
                        val serviceIntent = Intent(this, CastService::class.java).apply {
                            putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(CastService.EXTRA_RESULT_DATA, result.data!!)
                            putExtra(CastService.EXTRA_TARGET_IP, ip)
                            putExtra(CastService.EXTRA_QUALITY, quality.key)
                        }
                        ContextCompat.startForegroundService(this, serviceIntent)
                        binding.btnStart.text = getString(R.string.btn_stop)
                        CastLog.event("Streaming dimulai — foreground service aktif ($quality)")
                    }
                } else {
                    CastLog.event("Izin MediaProjection ditolak")
                    setStatus(getString(R.string.status_permission_denied))
                }
            } catch (t: Throwable) {
                CastLog.event("ERROR start: ${t.javaClass.simpleName}: ${t.message}")
                setStatus("Gagal: ${t.message}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.install(this)
        CrashCatcher.drainToLog(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        CastLog.event("Sender dibuka — UDP port ${Protocol.DEFAULT_PORT}")
        setStatus(getString(R.string.status_ready, Protocol.DEFAULT_PORT))

        // Daftar preset kualitas + pulihkan pilihan terakhir.
        val qualities = Quality.values()
        binding.spinQuality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualities.map { getString(it.labelRes) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val savedIndex = qualities.indexOfFirst { it.key == prefs.getString(PREFS_QUALITY, null) }
            .coerceAtLeast(0)
        binding.spinQuality.setSelection(savedIndex)
        binding.spinQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(PREFS_QUALITY, qualities[position].key).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TasirinCast", CastLog.snapshot()))
            Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnStart.setOnClickListener {
            if (CastService.isStreaming) {
                stopService(Intent(this, CastService::class.java))
                CastLog.event("Tombol stop ditekan — menghentikan service")
                binding.btnStart.text = getString(R.string.btn_start)
                setStatus(getString(R.string.status_ready, Protocol.DEFAULT_PORT))
            } else {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(pm.createScreenCaptureIntent())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CastService.onStatus = { msg -> setStatus(msg) }
        binding.btnStart.text = getString(if (CastService.isStreaming) R.string.btn_stop else R.string.btn_start)
    }

    override fun onStop() {
        super.onStop()
        CastService.onStatus = null
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    companion object {
        private const val PREFS_NAME = "cast_settings"
        private const val PREFS_QUALITY = "quality"
    }
}
