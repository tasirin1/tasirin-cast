package com.tasirin.castsender

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castsender.databinding.ActivityMainBinding
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CastLog.event("Sender dibuka — UDP port ${Protocol.DEFAULT_PORT}")
        binding.tvStatus.text = getString(R.string.status_ready, Protocol.DEFAULT_PORT)
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnStart.setOnClickListener {
            // TODO: MediaProjection + MediaCodec + UDP — lihat AGENTS.md (peta jalan).
            CastLog.event("Tombol start ditekan (streaming belum diimplementasikan)")
            Toast.makeText(this, R.string.toast_not_implemented, Toast.LENGTH_SHORT).show()
        }
    }
}
