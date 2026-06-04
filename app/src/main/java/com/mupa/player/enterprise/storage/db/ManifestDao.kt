package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ManifestDao {
    @Query("SELECT * FROM manifest WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getByDeviceId(deviceId: String): ManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ManifestEntity)

    @Query("DELETE FROM manifest")
    suspend fun deleteAll()
}
