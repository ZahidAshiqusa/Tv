package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.VodSection

@Entity(tableName = "vod_items")
data class VodItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val urduTitle: String,
    val sectionKey: String,
    val pageSlug: String,
    val altSlugs: String = "",
    val streamUrl: String = "",
    val posterUrl: String = "",
    val fallbackPosterUrl: String = "",
    val subtitleMeta: String = "",
    val accentHex: Long = 0xFF2563EB,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,
    val resolvedAt: Long = 0L
) {
    val section: VodSection
        get() = VodSection.fromKey(sectionKey)

    fun displayTitle(isUrdu: Boolean): String =
        if (isUrdu && urduTitle.isNotBlank()) urduTitle else title

    fun allCandidateSlugs(): List<String> = buildList {
        if (pageSlug.isNotBlank()) add(pageSlug.trim().trimStart('/'))
        altSlugs.split(",")
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() && it != pageSlug.trim().trimStart('/') }
            .forEach { add(it) }
    }
}
