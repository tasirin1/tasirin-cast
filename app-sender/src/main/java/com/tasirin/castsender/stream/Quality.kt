package com.tasirin.castsender.stream

import androidx.annotation.StringRes
import com.tasirin.castsender.R

/**
 * Preset kualitas video yang dipilih pengguna (layar utama sender).
 * Label UI dari string resources (Bahasa Inggris); dipakai untuk encoder
 * H.264 dan VirtualDisplay.
 */
enum class Quality(
    val key: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    @StringRes val labelRes: Int,
) {
    LOW("low", 640, 360, 2_500_000, R.string.quality_low),
    MEDIUM("medium", 854, 480, 4_000_000, R.string.quality_medium),
    HIGH("high", 1280, 720, 6_000_000, R.string.quality_high),
    ULTRA("ultra", 1920, 1080, 10_000_000, R.string.quality_ultra);

    companion object {
        fun fromKey(key: String?): Quality =
            values().firstOrNull { it.key == key } ?: HIGH
    }
}
