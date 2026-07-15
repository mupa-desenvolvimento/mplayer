package com.mupa.player.enterprise.managers

import android.content.Context
import android.net.Uri

class ArgosAgentIdentityClient(private val context: Context) {
    fun getDeviceId(): String {
        val uri = Uri.parse("content://com.mupa.agent.argos.identity/device_id")
        return runCatching {
            context.contentResolver.query(uri, arrayOf("device_id"), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use ""
                c.getString(c.getColumnIndexOrThrow("device_id"))?.trim().orEmpty()
            } ?: ""
        }.getOrDefault("")
    }
}

