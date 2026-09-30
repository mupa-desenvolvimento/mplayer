package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDownloadFailureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaDownloadFailureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MediaDownloadFailureEntity>)

    @Query("SELECT * FROM media_download_failures WHERE uploadedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<MediaDownloadFailureEntity>

    @Query("UPDATE media_download_failures SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAtEpochMs: Long)

    @Query("SELECT COUNT(*) FROM media_download_failures WHERE uploadedAtEpochMs IS NULL")
    suspend fun countPending(): Int
}
