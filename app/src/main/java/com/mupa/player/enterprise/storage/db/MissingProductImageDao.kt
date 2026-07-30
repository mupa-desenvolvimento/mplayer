package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MissingProductImageDao {
    // IGNORE: um EAN já reportado não deve ter a data do 1º relato resetada a cada scan repetido.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MissingProductImageEntity)

    @Query("SELECT * FROM missing_product_images WHERE uploadedAtEpochMs IS NULL ORDER BY firstReportedAtEpochMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<MissingProductImageEntity>

    @Query("SELECT COUNT(*) FROM missing_product_images WHERE uploadedAtEpochMs IS NULL")
    suspend fun getPendingCount(): Int

    @Query("UPDATE missing_product_images SET uploadedAtEpochMs = :uploadedAtEpochMs WHERE ean IN (:eans)")
    suspend fun markUploaded(eans: List<String>, uploadedAtEpochMs: Long)

    @Query("SELECT ean FROM missing_product_images ORDER BY firstReportedAtEpochMs ASC LIMIT :limit")
    suspend fun getEansToRecheck(limit: Int): List<String>

    @Query("DELETE FROM missing_product_images WHERE ean IN (:eans)")
    suspend fun deleteResolved(eans: List<String>)
}
