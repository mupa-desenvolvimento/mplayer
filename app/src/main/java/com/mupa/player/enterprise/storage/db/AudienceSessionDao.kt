package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudienceSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudienceSessionEntity)

    @Query("SELECT * FROM audience_sessions WHERE uploadedAtEpochMs IS NULL ORDER BY firstSeenEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<AudienceSessionEntity>

    @Query("UPDATE audience_sessions SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAtEpochMs: Long)
}
