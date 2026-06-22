package com.mupa.player.enterprise.audience

data class DetectedFace(
    val faceHash: String,
    val estimatedAge: Int?,
    val ageRange: String?,
    val gender: String?,
    val confidence: Float?,
    val isLooking: Boolean,
    val embedding: FloatArray? = null,
    val attentionDurationSeconds: Long = 0L,
    val boundingBox: android.graphics.Rect? = null,
)

data class AudienceFrameResult(
    val faces: List<DetectedFace>,
    val width: Int = 0,
    val height: Int = 0,
)

