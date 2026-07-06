package com.mupa.player.enterprise.audience

interface AudienceAnalyticsEngine {
    suspend fun init(): Boolean
    suspend fun processFrameJpegBase64(base64Jpeg: String, rotationDegrees: Int = 0): AudienceFrameResult
    suspend fun release()
}
