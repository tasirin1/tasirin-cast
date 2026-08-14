package com.tasirin.castreceiver

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityMainBinding
import com.tasirin.cast.protocol.CastLog
import com.tasirin.cast.protocol.Protocol

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CastLog.event("Receiver dibuka — menunggu di UDP port ${Protocol.DEFAULT_PORT}")
        binding.tvStatus.text = getString(R.string.status_waiting, Protocol.DEFAULT_PORT)
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // TODO: UDP socket + JitterBuffer + MediaCodec decoder -> surfaceView.
    }
}
