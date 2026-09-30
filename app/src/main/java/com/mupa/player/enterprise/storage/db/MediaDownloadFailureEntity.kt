package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_download_failures",
    indices = [
        Index(value = ["uploadedAtEpochMs"]),
        Index(value = ["createdAtEpochMs"]),
        Index(value = ["deviceId"]),
        Index(value = ["mediaId"]),
    ],
)
data class MediaDownloadFailureEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val mediaId: String,
    val mediaName: String?,
    val url: String,
    val errorReason: String,
    val createdAtEpochMs: Long,
    val uploadedAtEpochMs: Long? = null,
)
