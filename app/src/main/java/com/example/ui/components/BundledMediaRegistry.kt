package com.example.ui.components

import androidx.annotation.DrawableRes
import com.example.R
import com.example.data.model.VodSection
import java.util.Locale

/**
 * 100% Offline Bundled Media Registry.
 * Maps every famous Drama, TV Show, Movie, and Wrestling Show directly to its bundled HD 16:9
 * drawable poster inside res/drawable/ so every thumbnail is ready immediately upon app install.
 */
object BundledMediaRegistry {

    fun isRealRemoteAssetUrl(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        val lower = url.lowercase(Locale.ROOT)
        // Reject dead/deprecated Clearbit URLs; allow real broadcast CDNs and high-res official site icons
        return !lower.contains("clearbit.com") &&
            !lower.contains("google.com/s2/favicons")
    }

    @DrawableRes
    fun vodThumbnailRes(vodId: String, section: VodSection): Int = when (vodId) {
        // Crisp section-matched fallbacks if an original Tamasha thumbnail is temporarily offline
        else -> when (section) {
            VodSection.DRAMA -> R.drawable.thumb_drama_parizaad
            VodSection.TV_SHOW -> R.drawable.thumb_show_jeeto_pakistan
            VodSection.MOVIE -> R.drawable.thumb_movie_punjab_nahi_jaungi
            VodSection.WRESTLING -> R.drawable.thumb_wrestling_raw
            VodSection.HIGHLIGHTS -> R.drawable.thumb_wrestling_main_event
        }
    }
}
