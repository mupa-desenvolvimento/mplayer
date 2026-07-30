package com.mupa.player.enterprise.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "device_events",
    indices = [
        Index(value = ["uploadedAtEpochMs"]),
        Index(value = ["createdAtEpochMs"]),
    ],
)
data class DeviceEventEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val companyId: String?,
    val filial: String?,
    val eventType: String, // "heartbeat" | "repeated_scan_failure" | "connectivity_restored"
    val reason: String?, // heartbeat: "idle_timeout" | "state_change" | "requested"
    val ean: String?, // só em repeated_scan_failure
    val failCount: Int?, // só em repeated_scan_failure
    val offlineDurationSeconds: Int?, // só em connectivity_restored
    val createdAtEpochMs: Long,
    val uploadedAtEpochMs: Long?,
)
