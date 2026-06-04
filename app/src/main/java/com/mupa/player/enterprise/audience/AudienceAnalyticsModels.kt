package com.mupa.player.enterprise.audience

data class DetectedFace(
    val faceHash: String,
    val estimatedAge: Int?,
    val ageRange: String?,
    val gender: String?,
    val confidence: Float?,
    val isLooking: Boolean,
)

data class AudienceFrameResult(
    val faces: List<DetectedFace>,
)

