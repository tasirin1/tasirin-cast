package com.tasirin.castreceiver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.castreceiver.databinding.ActivityMainBinding
import com.tasirin.cast.protocol.Protocol

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = getString(R.string.status_waiting, Protocol.DEFAULT_PORT)
        // TODO: UDP socket + JitterBuffer + MediaCodec decoder -> surfaceView.
    }
}
