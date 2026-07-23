package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "missing_product_images",
    indices = [
        Index(value = ["uploadedAtEpochMs"]),
    ],
)
data class MissingProductImageEntity(
    @PrimaryKey val ean: String,
    val companyId: String?,
    val deviceId: String,
    val firstReportedAtEpochMs: Long,
    val uploadedAtEpochMs: Long?,
)
