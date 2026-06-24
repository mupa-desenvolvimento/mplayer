package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PriceQueryEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PriceQueryEventEntity)

    @Query("SELECT COUNT(*) FROM price_query_events WHERE ean = :ean AND createdAtEpochMs >= :sinceEpochMs")
    suspend fun countRecent(ean: String, sinceEpochMs: Long): Long

    @Query("SELECT * FROM price_query_events WHERE uploadedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<PriceQueryEventEntity>

    @Query("SELECT COUNT(*) FROM price_query_events WHERE uploadedAtEpochMs IS NULL")
    suspend fun getPendingCount(): Int

    @Query("UPDATE price_query_events SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAtEpochMs: Long)
}
