package com.mupa.player.enterprise.argos

import android.content.Context
import com.mupa.player.enterprise.managers.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ArgosPendingCommand(
    val commandId: String,
    val command: String,
    val priority: Int,
    val timestamp: Long,
    val params: JSONObject,
)

data class ArgosCommandResult(
    val commandId: String,
    val status: String,
    val message: String,
    val executedAt: Long,
)

class ArgosApiClient(
    private val context: Context,
    private val settingsManager: SettingsManager,
) {
    private fun baseApiUrl(): String {
        val configured = settingsManager.getArgosApiUrlCached().trim()
        if (configured.isBlank()) return ""
        val base = configured.trimEnd('/')
        return if (base.endsWith("/api", ignoreCase = true)) base else "$base/api"
    }

    fun fetchPendingCommands(deviceId: String): List<ArgosPendingCommand> {
        val base = baseApiUrl()
        if (base.isBlank()) return emptyList()
        val url = URL("$base/device/${deviceId.trim()}/pending-commands")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            settingsManager.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("X-Device-Id", deviceId.trim())
        }
        return runCatching {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return@runCatching emptyList()
            parsePendingCommands(text)
        }.getOrDefault(emptyList()).also {
            conn.disconnect()
        }
    }

    fun postCommandResult(deviceId: String, result: ArgosCommandResult): Boolean {
        val base = baseApiUrl()
        if (base.isBlank()) return false
        val url = URL("$base/device/${deviceId.trim()}/command-result")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            settingsManager.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("X-Device-Id", deviceId.trim())
        }
        return runCatching {
            val body = JSONObject()
                .put("commandId", result.commandId)
                .put("status", result.status)
                .put("message", result.message)
                .put("executedAt", result.executedAt)
                .toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            code in 200..299
        }.getOrDefault(false).also {
            conn.disconnect()
        }
    }

    private fun parsePendingCommands(raw: String): List<ArgosPendingCommand> {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return emptyList()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("commands")
                    ?: obj.optJSONArray("data")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
        val out = ArrayList<ArgosPendingCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val commandId = obj.optString("commandId", obj.optString("id", "")).trim()
            val command = obj.optString("command", obj.optString("comando", "")).trim()
            if (commandId.isBlank() || command.isBlank()) continue
            val priority = obj.optInt("priority", 0)
            val ts = when (val t = obj.opt("timestamp")) {
                is Number -> t.toLong()
                is String -> t.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
            val params = obj.optJSONObject("params")
                ?: obj.optJSONObject("parameters")
                ?: JSONObject()
            out.add(
                ArgosPendingCommand(
                    commandId = commandId,
                    command = command,
                    priority = priority,
                    timestamp = ts,
                    params = params,
                ),
            )
        }
        return out
    }
}

