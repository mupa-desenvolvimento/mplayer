package com.mupa.player.enterprise.utils

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfoProvider {
    fun getIpAddress(@Suppress("UNUSED_PARAMETER") context: Context): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr is Inet4Address
                }
                ?.hostAddress
                ?: ""
        }.getOrDefault("")
    }
}

