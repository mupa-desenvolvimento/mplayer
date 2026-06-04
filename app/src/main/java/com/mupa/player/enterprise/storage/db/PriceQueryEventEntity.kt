package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "price_query_events",
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["ean"]),
        Index(value = ["createdAtEpochMs"]),
        Index(value = ["uploadedAtEpochMs"]),
    ],
)
data class PriceQueryEventEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val ean: String,
    val createdAtEpochMs: Long,
    val responseTimeMs: Long,
    val fromCache: Boolean,
    val success: Boolean,
    val uploadedAtEpochMs: Long?,
)
