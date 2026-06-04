package com.mupa.player.enterprise.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class PlaybackProfile(
    val name: String,
    val isLowEnd: Boolean,
    val isHighEnd: Boolean,
    val videoMinBufferMs: Int,
    val videoMaxBufferMs: Int,
    val videoBufferForPlaybackMs: Int,
    val videoBufferForPlaybackAfterRebufferMs: Int,
    val firstFrameWaitMs: Long,
    val videoStallMs: Long,
    val watchdogPollMs: Long,
    val imageCrossfadeMs: Long,
    val videoSwitchMs: Long,
) {
    companion object {
        fun detect(context: Context): PlaybackProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val totalMem = mi.totalMem.takeIf { it > 0L } ?: 0L
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val sdk = Build.VERSION.SDK_INT
            val model = Build.MODEL.orEmpty()

            val isLowEnd =
                totalMem in 1L..2_400_000_000L ||
                    cores <= 4 ||
                    sdk <= 26 ||
                    model.contains("TC21", ignoreCase = true) ||
                    model.contains("TC22", ignoreCase = true) ||
                    model.contains("ET40", ignoreCase = true)

            val isHighEnd =
                !isLowEnd &&
                    totalMem >= 4_000_000_000L &&
                    cores >= 8 &&
                    sdk >= 29

            return if (isLowEnd) {
                PlaybackProfile(
                    name = "low_end",
                    isLowEnd = true,
                    isHighEnd = false,
                    videoMinBufferMs = 800,
                    videoMaxBufferMs = 5_000,
                    videoBufferForPlaybackMs = 250,
                    videoBufferForPlaybackAfterRebufferMs = 750,
                    firstFrameWaitMs = 1_200L,
                    videoStallMs = 5_500L,
                    watchdogPollMs = 400L,
                    imageCrossfadeMs = 200L,
                    videoSwitchMs = 0L,
                )
            } else {
                PlaybackProfile(
                    name = "balanced",
                    isLowEnd = false,
                    isHighEnd = isHighEnd,
                    videoMinBufferMs = 1_200,
                    videoMaxBufferMs = 10_000,
                    videoBufferForPlaybackMs = 400,
                    videoBufferForPlaybackAfterRebufferMs = 1_000,
                    firstFrameWaitMs = 1_500L,
                    videoStallMs = 6_000L,
                    watchdogPollMs = 500L,
                    imageCrossfadeMs = if (isHighEnd) 300L else 280L,
                    videoSwitchMs = 0L,
                )
            }
        }
    }
}
