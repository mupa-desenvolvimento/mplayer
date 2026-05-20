package com.mupa.player.enterprise.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock

data class DeviceSnapshot(
    val deviceUuid: String,
    val ip: String,
    val batteryPercent: Int,
    val isOnline: Boolean,
    val uptimeMs: Long,
    val usedMemoryMb: Long,
    val maxMemoryMb: Long,
) {
    fun asMultilineText(lastCommand: String, lastAck: String): String {
        val uptimeSeconds = uptimeMs / 1000
        return buildString {
            append("device_uuid: ").append(deviceUuid).append('\n')
            append("ip: ").append(ip).append('\n')
            append("internet: ").append(if (isOnline) "online" else "offline").append('\n')
            append("bateria: ").append(batteryPercent).append("%\n")
            append("uptime: ").append(uptimeSeconds).append("s\n")
            append("memoria: ").append(usedMemoryMb).append("MB / ").append(maxMemoryMb).append("MB\n")
            append("ultimo_comando: ").append(lastCommand)
            if (lastAck.isNotBlank()) {
                append('\n')
                append("ack: ").append(lastAck)
            }
        }
    }
}

object DeviceInfoProvider {
    fun getSnapshot(context: Context, deviceUuid: String): DeviceSnapshot {
        val ip = NetworkInfoProvider.getIpAddress(context)
        val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)

        return DeviceSnapshot(
            deviceUuid = deviceUuid,
            ip = ip,
            batteryPercent = battery,
            isOnline = isOnline,
            uptimeMs = SystemClock.elapsedRealtime(),
            usedMemoryMb = used,
            maxMemoryMb = max,
        )
    }
}
