package com.tasirin.castreceiver

import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityMainBinding
import com.tasirin.castreceiver.net.ScreenReceiver
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol
import kotlin.math.min

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
        binding.btnStop.setOnClickListener { stopReceiver() }
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
        // Fullscreen saat streaming: sembunyikan panel kontrol & hint,
        // tampilkan tombol Stop, dan sembunyikan system bar.
        binding.controlsPanel.visibility = View.GONE
        binding.tvHint.visibility = View.GONE
        binding.btnStop.visibility = View.VISIBLE
        binding.btnStart.text = getString(R.string.btn_stop)
        enterImmersive()
        setStatus(getString(R.string.status_starting, Protocol.DEFAULT_PORT))
    }

    private fun stopReceiver() {
        receiver?.stop()
        receiver = null
        surface?.release()
        surface = null
        binding.btnStart.text = getString(R.string.btn_start)
        binding.controlsPanel.visibility = View.VISIBLE
        binding.tvHint.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        exitImmersive()
        setStatus(getString(R.string.status_waiting, Protocol.DEFAULT_PORT))
    }

    // SurfaceTextureListener: kalau surface dibuat ulang saat receiver jalan,
    // restart receiver dengan surface baru supaya render tidak ke surface mati.
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateTextureTransform()
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

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateTextureTransform()
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    // Fokus kembali saat streaming: pastikan system bar tetap tersembunyi.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && receiver != null) enterImmersive()
    }

    /**
     * Video encoder selalu 1280x720 (16:9). Kalau ukuran view tidak sama persis
     * aspeknya, TextureView akan meregangkan gambar. Transform ini memetakan
     * video secara fit-center supaya tidak terdistorsi ("tertekan" ke atas).
     */
    private fun updateTextureTransform() {
        val view = binding.textureView
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val videoW = 1280f
        val videoH = 720f
        val scale = min(viewW / videoW, viewH / videoH)
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate((viewW - videoW * scale) / 2f, (viewH - videoH * scale) / 2f)
        view.setTransform(matrix)
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun exitImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
    }
}
