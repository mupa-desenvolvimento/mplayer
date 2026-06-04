package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey val ean: String,
    val productJson: String,
    val updatedAtEpochMs: Long,
)

