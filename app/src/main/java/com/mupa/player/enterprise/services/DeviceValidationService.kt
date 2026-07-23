package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class DeviceValidationResult {
    data class Found(val cache: DeviceCache, val heartbeatRequested: Boolean = false) : DeviceValidationResult()
    data object NotFound : DeviceValidationResult()
    data object NotConfigured : DeviceValidationResult()
    data class Error(val message: String) : DeviceValidationResult()
}

class DeviceValidationService(private val context: Context) {
    private val cacheManager = DeviceCacheManager(context)
    private val api = SupabaseClient.createApi()

    suspend fun validateDevice(deviceId: String): DeviceValidationResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext DeviceValidationResult.NotConfigured

        return@withContext runCatching {
            val responseText = api
                .postJson(
                    url = BuildConfig.SUPABASE_DEVICE_RPC_URL,
                    body = mapOf("p_serial" to deviceId),
                )
                .string()

            val parsed = parseDeviceResponse(responseText)
                ?: return@runCatching DeviceValidationResult.NotFound

            cacheManager.save(parsed)
            val heartbeatRequested = runCatching { parseHeartbeatRequested(responseText) }.getOrDefault(false)
            DeviceValidationResult.Found(parsed, heartbeatRequested)
        }.getOrElse {
            DeviceValidationResult.Error(it.javaClass.simpleName)
        }
    }

    /**
     * Heartbeat sob demanda (item 3 do monitoramento proativo): a plataforma marca
     * `heartbeat_requested=true` na mesma resposta do RPC `get_dispositivo_por_serial` — sem
     * canal novo, aproveita a chamada que já roda a cada 1h. Ver
     * `MUPA_PLATFORM_DEVICE_EVENTS_CONTRACT.md`.
     */
    private fun parseHeartbeatRequested(json: String): Boolean {
        val trimmed = json.trim()
        if (trimmed.isBlank() || trimmed == "null") return false
        val obj = when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return false
                arr.optJSONObject(0) ?: return false
            }
            else -> return false
        }
        return obj.optBoolean("heartbeat_requested", false)
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
        val company = obj.optString("company_id", "").ifBlank { obj.optString("empresa", "").ifBlank { obj.optString("company", "") } }.trim()
        val tenant = obj.optString("tenant_id", "").ifBlank { obj.optString("tenant", "") }.trim()
        val dbId = obj.optLong("id", 0L)
        val licenseType = obj.optString("tipo_da_licenca", "").takeIf { it.isNotBlank() && it != "null" }

        return DeviceCache(
            deviceDbId = dbId,
            deviceId = serial,
            deviceName = name,
            filial = filial,
            company = company,
            companyCode = company,
            companyName = obj.optString("empresa_nome", "").ifBlank { obj.optString("company_name", "") }.trim(),
            tenant = tenant,
            lastSyncEpochMs = System.currentTimeMillis(),
            deviceRegistered = true,
            tipoDaLicenca = licenseType,
        )
    }
}
