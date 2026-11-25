package com.example.data.model

import com.example.localization.AppStrings

enum class VideoQuality(
    val key: String,
    val badgeLabel: String,
    val maxHeightPx: Int,
    val maxBitrateBps: Int
) {
    AUTO(
        key = "auto",
        badgeLabel = "AUTO",
        maxHeightPx = Int.MAX_VALUE,
        maxBitrateBps = Int.MAX_VALUE
    ),
    P240(
        key = "240p",
        badgeLabel = "240p",
        maxHeightPx = 240,
        maxBitrateBps = 450_000
    ),
    P360(
        key = "360p",
        badgeLabel = "360p",
        maxHeightPx = 360,
        maxBitrateBps = 950_000
    ),
    P720(
        key = "720p",
        badgeLabel = "720p",
        maxHeightPx = 720,
        maxBitrateBps = 3_200_000
    );

    fun title(strings: AppStrings): String = when (this) {
        AUTO -> strings.qualityAuto
        P240 -> strings.quality240
        P360 -> strings.quality360
        P720 -> strings.quality720
    }

    fun subtitle(strings: AppStrings): String = when (this) {
        AUTO -> strings.qualityAutoDesc
        P240 -> strings.quality240Desc
        P360 -> strings.quality360Desc
        P720 -> strings.quality720Desc
    }

    companion object {
        fun fromKey(key: String?): VideoQuality =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AUTO
    }
}
