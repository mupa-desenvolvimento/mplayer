package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.PowerManager

class WakeLockManager(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "MupaPlayerEnterprise:WakeLock",
    )

    fun acquire() {
        if (wakeLock.isHeld) return
        wakeLock.acquire(60 * 60 * 1000L)
    }

    fun release() {
        if (!wakeLock.isHeld) return
        wakeLock.release()
    }
}

