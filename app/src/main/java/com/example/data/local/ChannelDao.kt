package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sortOrder ASC, name ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllChannelsSnapshot(): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun getChannelCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(channels: List<ChannelEntity>)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE channels SET streamUrl = :streamUrl, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateResolvedStream(id: String, streamUrl: String, resolvedAt: Long)

    @Query("UPDATE channels SET logoUrl = :logoUrl WHERE id = :id")
    suspend fun updateLogoUrl(id: String, logoUrl: String)
}
