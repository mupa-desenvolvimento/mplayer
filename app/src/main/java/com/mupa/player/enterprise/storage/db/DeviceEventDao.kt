package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeviceEventEntity)

    @Query("SELECT * FROM device_events WHERE uploadedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<DeviceEventEntity>

    @Query("UPDATE device_events SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAtEpochMs: Long)
}
