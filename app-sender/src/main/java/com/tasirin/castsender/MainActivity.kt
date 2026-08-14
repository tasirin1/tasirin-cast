package com.tasirin.castsender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castsender.databinding.ActivityMainBinding
import com.tasirin.castsender.stream.ScreenStreamer
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var streamer: ScreenStreamer? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = pm.getMediaProjection(result.resultCode, result.data!!)
                if (projection != null) {
                    startStreaming(projection)
                } else {
                    CastLog.event("Gagal membuat MediaProjection")
                    setStatus(getString(R.string.status_projection_failed))
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
            if (streamer == null) {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(pm.createScreenCaptureIntent())
            } else {
                stopStreaming()
            }
        }
    }

    private fun startStreaming(projection: MediaProjection) {
        val ipText = binding.etIp.text.toString().trim()
        val targetIp = if (ipText.isEmpty()) {
            null
        } else {
            runCatching { InetAddress.getByName(ipText) }.getOrNull()
        }
        if (ipText.isNotEmpty() && targetIp == null) {
            CastLog.event("IP receiver tidak valid: $ipText")
            Toast.makeText(this, R.string.toast_invalid_ip, Toast.LENGTH_SHORT).show()
            projection.stop()
            return
        }
        val s = ScreenStreamer(this, projection, targetIp) { msg -> setStatus(msg) }
        streamer = s
        s.start()
        binding.btnStart.text = getString(R.string.btn_stop)
    }

    private fun stopStreaming() {
        streamer?.stop()
        streamer = null
        binding.btnStart.text = getString(R.string.btn_start)
        setStatus(getString(R.string.status_ready, Protocol.DEFAULT_PORT))
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
