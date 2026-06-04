package com.mupa.player.enterprise.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

data class PlayerTelemetry(
    val itemId: String?,
    val itemType: String?,
    val playbackState: Int?,
    val isPlaying: Boolean?,
    val renderedFps: Float?,
    val droppedFrames: Long?,
    val cpuPercent: Float?,
    val usedRamMb: Int?,
    val totalRamMb: Int?,
    val temperatureC: Float?,
    val lastError: String?,
)

fun interface PlayerTelemetrySink {
    fun onTelemetry(t: PlayerTelemetry)
}

internal class SystemMetrics(private val context: Context) {
    private var lastCpuMs: Long = 0L
    private var lastWallMs: Long = 0L

    fun readCpuPercent(): Float? {
        val nowCpu = android.os.Process.getElapsedCpuTime()
        val nowWall = SystemClock.elapsedRealtime()
        val prevCpu = lastCpuMs
        val prevWall = lastWallMs
        lastCpuMs = nowCpu
        lastWallMs = nowWall
        if (prevWall == 0L) return null
        val dtWall = (nowWall - prevWall).coerceAtLeast(1L)
        val dtCpu = (nowCpu - prevCpu).coerceAtLeast(0L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pct = (dtCpu.toFloat() / dtWall.toFloat()) * (100f / cores.toFloat())
        return pct.coerceIn(0f, 100f)
    }

    fun readRamMb(): Pair<Int?, Int?> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null to null
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = (mi.totalMem / (1024L * 1024L)).toInt().takeIf { it > 0 }
        val availMb = (mi.availMem / (1024L * 1024L)).toInt().takeIf { it > 0 }
        val usedMb = if (totalMb != null && availMb != null) (totalMb - availMb).coerceAtLeast(0) else null
        return usedMb to totalMb
    }

    fun readTemperatureC(): Float? {
        val i = runCatching {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
        }.getOrNull() ?: return null
        val t = i.getIntExtra("temperature", Int.MIN_VALUE)
        if (t == Int.MIN_VALUE) return null
        val c = t / 10f
        if (!c.isFinite()) return null
        return c.takeIf { it > -20f && it < 120f }
    }

    fun buildDeviceTag(): String {
        val sdk = Build.VERSION.SDK_INT
        val model = Build.MODEL.orEmpty()
        val manuf = Build.MANUFACTURER.orEmpty()
        return "$manuf $model (sdk $sdk)".trim()
    }
}
