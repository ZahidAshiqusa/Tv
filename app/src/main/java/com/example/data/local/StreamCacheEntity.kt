package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent SQLite (Room) fast stream cache storing freshly resolved live TV & VOD
 * playback URLs with their exact sanitized headers and token expiry timestamps.
 * Enables near-instant (<50ms) channel/VOD startup across taps and app restarts.
 */
@Entity(tableName = "stream_cache")
data class StreamCacheEntity(
    @PrimaryKey val targetId: String,
    val streamUrl: String,
    val headersJson: String = "{}",
    val sourceKindKey: String = "TAMASHA_HLS",
    val resolvedAt: Long = 0L,
    val expiresAt: Long = 0L
)
