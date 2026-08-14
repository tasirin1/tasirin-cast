package com.tasirin.castsender.stream

import androidx.annotation.StringRes
import com.tasirin.castsender.R

/**
 * Preset kualitas video (layar utama sender). Bukan resolusi tetap: capture
 * mengikuti aspek layar asli (potret = video potret) dan diskalakan agar sisi
 * terpanjang tidak melebihi [maxDimension].
 */
enum class Quality(
    val key: String,
    val maxDimension: Int,
    val bitrate: Int,
    @StringRes val labelRes: Int,
) {
    LOW("low", 640, 2_500_000, R.string.quality_low),
    MEDIUM("medium", 854, 4_000_000, R.string.quality_medium),
    HIGH("high", 1280, 6_000_000, R.string.quality_high),
    ULTRA("ultra", 1920, 10_000_000, R.string.quality_ultra);

    companion object {
        fun fromKey(key: String?): Quality =
            values().firstOrNull { it.key == key } ?: HIGH
    }
}
