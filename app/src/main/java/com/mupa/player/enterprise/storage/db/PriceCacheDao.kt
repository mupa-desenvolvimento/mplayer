package com.mupa.player.enterprise.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PriceCacheDao {
    @Query("SELECT * FROM price_cache WHERE ean = :ean LIMIT 1")
    suspend fun getByEan(ean: String): PriceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PriceCacheEntity)
}

