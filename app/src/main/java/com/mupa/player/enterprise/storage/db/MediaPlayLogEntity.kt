package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_play_logs",
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["mediaId"]),
        Index(value = ["playedAtEpochMs"]),
    ],
)
data class MediaPlayLogEntity(
    @PrimaryKey val id: String,
    val deviceDbId: Long,
    val deviceId: String,
    val mediaId: String,
    val mediaName: String,
    val mediaType: String,
    val playedAtEpochMs: Long,
    val durationSeconds: Long,
    val uploadedAtEpochMs: Long?,
)
