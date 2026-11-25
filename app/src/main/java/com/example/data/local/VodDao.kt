package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_items ORDER BY sortOrder ASC, title ASC")
    fun observeAllVodItems(): Flow<List<VodItemEntity>>

    @Query("SELECT * FROM vod_items ORDER BY sortOrder ASC, title ASC")
    suspend fun getAllVodSnapshot(): List<VodItemEntity>

    @Query("SELECT COUNT(*) FROM vod_items")
    suspend fun getVodCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VodItemEntity>)

    @Query("UPDATE vod_items SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE vod_items SET streamUrl = :streamUrl, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateResolvedStream(id: String, streamUrl: String, resolvedAt: Long)

    @Query("UPDATE vod_items SET posterUrl = :posterUrl WHERE id = :id")
    suspend fun updatePosterUrl(id: String, posterUrl: String)

    @Query("DELETE FROM vod_items")
    suspend fun clearAll()
}
