package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed class DeviceValidationResult {
    data class Found(val cache: DeviceCache) : DeviceValidationResult()
    data object NotFound : DeviceValidationResult()
    data object NotConfigured : DeviceValidationResult()
    data class Error(val message: String) : DeviceValidationResult()
}

class DeviceValidationService(private val context: Context) {
    private val cacheManager = DeviceCacheManager(context)

    suspend fun validateDevice(deviceId: String): DeviceValidationResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext DeviceValidationResult.NotConfigured

        val url = URL(BuildConfig.SUPABASE_DEVICE_RPC_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", token)
            setRequestProperty("Authorization", "Bearer $token")
        }

        return@withContext runCatching {
            val body = JSONObject().put("p_serial", deviceId).toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val suffix = if (responseText.isBlank()) "" else " $responseText"
                return@runCatching DeviceValidationResult.Error("http_$code$suffix")
            }

            val parsed = parseDeviceResponse(responseText)
                ?: return@runCatching DeviceValidationResult.NotFound

            cacheManager.save(parsed)
            DeviceValidationResult.Found(parsed)
        }.getOrElse {
            DeviceValidationResult.Error(it.javaClass.simpleName)
        }.also {
            conn.disconnect()
        }
    }

    private fun parseDeviceResponse(json: String): DeviceCache? {
        val trimmed = json.trim()
        if (trimmed.isBlank() || trimmed == "null") return null

        val obj = when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return null
                arr.optJSONObject(0) ?: return null
            }
            else -> return null
        }

        val serial = obj.optString("serial", "").ifBlank { obj.optString("device_id", "") }
        if (serial.isBlank()) return null

        val name = obj.optString("apelido_interno", "")
        val filial = obj.optString("num_filial", "")
        val company = obj.optString("empresa", "").ifBlank { obj.optString("company", "") }
        val tenant = obj.optString("tenant_id", "").ifBlank { obj.optString("tenant", "") }
        val dbId = obj.optLong("id", 0L)

        return DeviceCache(
            deviceDbId = dbId,
            deviceId = serial,
            deviceName = name,
            filial = filial,
            company = company,
            companyCode = obj.optString("empresa", ""),
            companyName = obj.optString("empresa_nome", ""),
            tenant = tenant,
            lastSyncEpochMs = System.currentTimeMillis(),
            deviceRegistered = true,
        )
    }
}
