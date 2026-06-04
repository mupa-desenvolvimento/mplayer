package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audience_sessions",
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["faceHash"]),
        Index(value = ["firstSeenEpochMs"]),
    ],
)
data class AudienceSessionEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val faceHash: String,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val viewDurationSeconds: Long,
    val lookCount: Int,
    val estimatedAge: Int?,
    val ageRange: String?,
    val gender: String?,
    val confidence: Float?,
    val hour: Int,
    val weekday: String,
    val contentPlaying: String?,
    val playlist: String?,
    val uploadedAtEpochMs: Long?,
)
