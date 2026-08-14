package com.tasirin.castreceiver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityMainBinding
import com.tasirin.castreceiver.net.ScreenReceiver
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var receiver: ScreenReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CastLog.event("Receiver dibuka — menunggu di UDP port ${Protocol.DEFAULT_PORT}")
        binding.tvStatus.text = getString(R.string.status_waiting, Protocol.DEFAULT_PORT)
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnStart.setOnClickListener {
            if (receiver == null) startReceiver() else stopReceiver()
        }
    }

    private fun startReceiver() {
        val surface = binding.surfaceView.holder.surface
        if (!surface.isValid) {
            CastLog.event("Surface belum siap — coba lagi")
            Toast.makeText(this, R.string.toast_surface_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val r = ScreenReceiver(surface) { msg -> setStatus(msg) }
        receiver = r
        r.start()
        binding.btnStart.text = getString(R.string.btn_stop)
        setStatus(getString(R.string.status_starting, Protocol.DEFAULT_PORT))
    }

    private fun stopReceiver() {
        receiver?.stop()
        receiver = null
        binding.btnStart.text = getString(R.string.btn_start)
        setStatus(getString(R.string.status_waiting, Protocol.DEFAULT_PORT))
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
    }
}
