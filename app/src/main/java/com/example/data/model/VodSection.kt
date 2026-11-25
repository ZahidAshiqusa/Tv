package com.example.data.model

import com.example.localization.AppStrings

enum class VodSection(val storageKey: String) {
    DRAMA("DRAMA"),
    TV_SHOW("TV_SHOW"),
    MOVIE("MOVIE"),
    WRESTLING("WRESTLING"),
    HIGHLIGHTS("HIGHLIGHTS");

    fun label(strings: AppStrings): String = when (this) {
        DRAMA -> strings.sectionDramas
        TV_SHOW -> strings.sectionTvShows
        MOVIE -> strings.sectionMovies
        WRESTLING -> strings.sectionWrestling
        HIGHLIGHTS -> strings.sectionHighlights
    }

    companion object {
        fun fromKey(key: String?): VodSection =
            entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: TV_SHOW
    }
}
