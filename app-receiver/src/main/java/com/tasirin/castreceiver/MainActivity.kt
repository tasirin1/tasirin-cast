package com.tasirin.castreceiver

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityMainBinding
import com.tasirin.castreceiver.net.ScreenReceiver
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityMainBinding
    private var receiver: ScreenReceiver? = null
    private var surface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.install(this)
        CrashCatcher.drainToLog(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CastLog.event("Receiver dibuka — menunggu di UDP port ${Protocol.DEFAULT_PORT}")
        binding.tvStatus.text = getString(R.string.status_waiting, Protocol.DEFAULT_PORT)
        binding.textureView.surfaceTextureListener = this
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnStart.setOnClickListener {
            if (receiver == null) startReceiver() else stopReceiver()
        }
    }

    private fun startReceiver() {
        val st = binding.textureView.surfaceTexture
        if (st == null) {
            CastLog.event("Surface belum siap — coba lagi")
            Toast.makeText(this, R.string.toast_surface_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        surface?.release()
        val s = Surface(st)
        surface = s
        val r = ScreenReceiver(s) { msg -> setStatus(msg) }
        receiver = r
        r.start()
        binding.btnStart.text = getString(R.string.btn_stop)
        setStatus(getString(R.string.status_starting, Protocol.DEFAULT_PORT))
    }

    private fun stopReceiver() {
        receiver?.stop()
        receiver = null
        surface?.release()
        surface = null
        binding.btnStart.text = getString(R.string.btn_start)
        setStatus(getString(R.string.status_waiting, Protocol.DEFAULT_PORT))
    }

    // SurfaceTextureListener: kalau surface dibuat ulang saat receiver jalan,
    // restart receiver dengan surface baru supaya render tidak ke surface mati.
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (receiver != null) {
            CastLog.event("Surface dibuat ulang — restart receiver")
            stopReceiver()
            startReceiver()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopReceiver()
        return true
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
    }
}
