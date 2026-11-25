package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ChannelCategory

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val urduName: String,
    val streamUrl: String,
    val tamashaSlug: String,
    val tamashaAltSlugs: String, // comma-separated fallback slugs on tamashaweb.com
    val tamashaStreamKey: String, // e.g., "vsat-geonews-abr"
    val publicFallbackUrl: String = "",
    val categoryKey: String,
    val logoUrl: String,
    val fallbackLogoUrl: String,
    val accentHex: Long,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,
    val resolvedAt: Long = 0L
) {
    val category: ChannelCategory
        get() = ChannelCategory.fromKey(categoryKey)

    fun allCandidateSlugs(): List<String> = buildList {
        if (tamashaSlug.isNotBlank()) add(tamashaSlug.trim())
        tamashaAltSlugs.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != tamashaSlug.trim() }
            .forEach { add(it) }
    }

    fun displayName(isUrdu: Boolean): String =
        if (isUrdu && urduName.isNotBlank()) urduName else name
}
