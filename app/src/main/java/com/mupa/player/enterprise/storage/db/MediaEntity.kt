package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media",
    indices = [
        Index(value = ["remoteUrl"], unique = true),
        Index(value = ["localPath"], unique = true),
    ],
)
data class MediaEntity(
    @PrimaryKey val mediaId: String,
    val type: String,
    val remoteUrl: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val updatedAtEpochMs: Long,
)
