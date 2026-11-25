package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StreamCacheDao {
    @Query("SELECT * FROM stream_cache WHERE expiresAt > :now")
    suspend fun getAllValid(now: Long): List<StreamCacheEntity>

    @Query("SELECT * FROM stream_cache WHERE targetId = :targetId LIMIT 1")
    suspend fun getByTargetId(targetId: String): StreamCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: StreamCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<StreamCacheEntity>)

    @Query("DELETE FROM stream_cache WHERE targetId = :targetId")
    suspend fun deleteByTargetId(targetId: String)

    @Query("DELETE FROM stream_cache WHERE streamUrl = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM stream_cache WHERE expiresAt <= :now")
    suspend fun pruneExpired(now: Long)
}
