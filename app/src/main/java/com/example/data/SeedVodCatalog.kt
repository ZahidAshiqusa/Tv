package com.example.data

import com.example.data.local.VodItemEntity

/**
 * Intentionally empty — ONLY real free Dramas, TV Shows, Movies, Wrestling Shows, and Highlights
 * that actually exist live on tamashaweb.com are fetched and displayed (with their original
 * thumbnails and working playable streams). No guessed or non-existent placeholder titles.
 */
object SeedVodCatalog {
    fun all(): List<VodItemEntity> = emptyList()
}
