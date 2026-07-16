package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaPlayLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaPlayLogEntity)

    @Query("SELECT * FROM media_play_logs WHERE uploadedAtEpochMs IS NULL ORDER BY playedAtEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<MediaPlayLogEntity>

    @Query("UPDATE media_play_logs SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAtEpochMs: Long)

    @Query("SELECT COUNT(*) FROM media_play_logs WHERE uploadedAtEpochMs IS NULL")
    suspend fun getPendingCount(): Int
}
