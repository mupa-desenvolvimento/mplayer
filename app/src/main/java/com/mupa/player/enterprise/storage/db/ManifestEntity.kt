package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manifest")
data class ManifestEntity(
    @PrimaryKey val deviceId: String,
    val json: String,
    val updatedAtEpochMs: Long,
)
