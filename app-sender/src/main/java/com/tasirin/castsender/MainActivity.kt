package com.tasirin.castsender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tasirin.castsender.databinding.ActivityMainBinding
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                    val serviceIntent = Intent(this, CastService::class.java).apply {
                        putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(CastService.EXTRA_RESULT_DATA, result.data!!)
                        putExtra(CastService.EXTRA_TARGET_IP, ip)
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                    CastLog.event("Streaming dimulai — foreground service aktif")
                }
            } else {
                CastLog.event("Izin MediaProjection ditolak")
                setStatus(getString(R.string.status_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CastLog.event("Sender dibuka — UDP port ${Protocol.DEFAULT_PORT}")
        setStatus(getString(R.string.status_ready, Protocol.DEFAULT_PORT))
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
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
}
