package com.example.data.model

import com.example.localization.AppStrings

enum class ChannelCategory(val storageKey: String) {
    ALL("All"),
    NEWS("News"),
    SPORTS("Sports"),
    ENTERTAINMENT("Entertainment"),
    ISLAMIC("Islamic"),
    MOVIES("Movies");

    fun label(strings: AppStrings): String = when (this) {
        ALL -> strings.allCategories
        NEWS -> strings.catNews
        SPORTS -> strings.catSports
        ENTERTAINMENT -> strings.catEntertainment
        ISLAMIC -> strings.catIslamic
        MOVIES -> strings.catMovies
    }

    companion object {
        val assignableCategories: List<ChannelCategory> =
            listOf(NEWS, SPORTS, ENTERTAINMENT, ISLAMIC, MOVIES)

        fun fromKey(key: String?): ChannelCategory {
            val normalized = key?.trim()?.lowercase() ?: return ENTERTAINMENT
            return when {
                normalized.contains("news") || normalized.contains("خبر") -> NEWS
                normalized.contains("sport") || normalized.contains("cricket") || normalized.contains("cup") -> SPORTS
                normalized.contains("islam") || normalized.contains("paigham") || normalized.contains("qtv") || normalized.contains("madani") -> ISLAMIC
                normalized.contains("movie") || normalized.contains("doc") || normalized.contains("discovery") || normalized.contains("film") -> MOVIES
                normalized.contains("all") -> ALL
                else -> ENTERTAINMENT
            }
        }
    }
}
