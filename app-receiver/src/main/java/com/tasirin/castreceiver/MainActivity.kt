package com.tasirin.castreceiver

import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
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
    private var surfaceTexture: SurfaceTexture? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Ukuran frame video; diperbarui saat decoder melaporkan ukuran asli.
    private var videoWidth = 1280
    private var videoHeight = 720

    // Ukuran layar asli sender (dari paket SCREEN_INFO); aspek tampilan
    // mengikuti ini supaya gambar tidak gepeng walau encoder/decoder menjepit.
    private var screenWidth = 1280
    private var screenHeight = 720

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

        // TextureView selalu me-reset ukuran buffer SurfaceTexture ke ukuran
        // view saat layout berubah (mis. mode immersive menyembunyikan system
        // bar). Listener ini mengembalikannya ke ukuran video asli + ulang
        // transform supaya tampilan tetap fit-center.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight) }
            updateTextureTransform()
        }
        binding.textureView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        layoutListener = listener
    }

    private fun startReceiver() {
        val st = surfaceTexture ?: binding.textureView.surfaceTexture
        if (st == null) {
            CastLog.event("Surface belum siap — coba lagi")
            Toast.makeText(this, R.string.toast_surface_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        surfaceTexture = st
        // Buffer texture WAJIB selebar video asli — kalau dibiarkan selebar view,
        // video sudah ter-stretch penuh lalu transform fit-center justru
        // memperbesar (jadi tidak ke tengah & ke-zoom-in).
        st.setDefaultBufferSize(videoWidth, videoHeight)
        surface?.release()
        val s = Surface(st)
        surface = s
        val r = ScreenReceiver(
            s,
            onStatus = { msg -> setStatus(msg) },
            onVideoSize = { w, h -> handleVideoSize(w, h) },
            onScreenSize = { w, h -> handleScreenSize(w, h) },
        )
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

    // Dipanggil dari thread decoder saat ukuran output berubah (resolusi asli
    // streaming, misal 1080p). Semua akses view di posting ke main thread.
    private fun handleScreenSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        runOnUiThread {
            if (screenWidth != w || screenHeight != h) {
                screenWidth = w
                screenHeight = h
                updateTextureTransform()
                CastLog.event("Aspek layar sender disetel ke ${w}x${h}")
            }
        }
    }

    private fun handleVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        runOnUiThread {
            if (videoWidth != w || videoHeight != h) {
                videoWidth = w
                videoHeight = h
                runCatching { surfaceTexture?.setDefaultBufferSize(w, h) }
                updateTextureTransform()
                CastLog.event("Video disetel ke ${w}x${h} — transform diperbarui")
            }
        }
    }

    // SurfaceTextureListener: kalau surface dibuat ulang saat receiver jalan,
    // restart receiver dengan surface baru supaya render tidak ke surface mati.
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        this.surfaceTexture = surfaceTexture
        runCatching { surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight) }
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
     * Video dirender ke buffer SurfaceTexture berukuran frame (videoWidth x
     * videoHeight). Transform ini:
     * 1) meregangkan buffer ke aspek layar sender (screenWidth x screenHeight)
     *    — mengoreksi encoder/decoder yang menjepit resolusi (mis. 720x1600
     *    menjadi 720x1088) sehingga isi layar tidak gepeng;
     * 2) memetakannya fit-center ke view.
     */
    private fun updateTextureTransform() {
        val view = binding.textureView
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val bufW = videoWidth.toFloat()
        val bufH = videoHeight.toFloat()
        val scrW = screenWidth.toFloat()
        val scrH = screenHeight.toFloat()
        val fit = min(viewW / scrW, viewH / scrH)
        val matrix = Matrix()
        matrix.postScale(scrW / bufW, scrH / bufH)
        matrix.postScale(fit, fit)
        matrix.postTranslate((viewW - scrW * fit) / 2f, (viewH - scrH * fit) / 2f)
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
        val observer = binding.textureView.viewTreeObserver
        layoutListener?.let { if (observer.isAlive) observer.removeOnGlobalLayoutListener(it) }
        layoutListener = null
    }
}
