package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class DeviceRegistrationPayload(
    val apelidoInterno: String,
    val serial: String,
    val empresaId: String,
    val empresaCode: String,
    val numFilial: String,
)

sealed class DeviceRegistrationResult {
    data object Success : DeviceRegistrationResult()
    data object NotConfigured : DeviceRegistrationResult()
    data class Error(val message: String) : DeviceRegistrationResult()
}

class DeviceRegistrationService(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    suspend fun register(payload: DeviceRegistrationPayload): DeviceRegistrationResult =
        withContext(Dispatchers.IO) {
            val token = BuildConfig.SUPABASE_TOKEN.trim()
            if (token.isBlank()) return@withContext DeviceRegistrationResult.NotConfigured

            val url = URL(BuildConfig.SUPABASE_CREATE_DEVICE_RPC_URL)
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
                val inner = JSONObject()
                    .put("apelido_interno", payload.apelidoInterno)
                    .put("apps_instalados", org.json.JSONArray())
                    .put("pin", payload.empresaCode)
                    .put("serial", payload.serial)
                    .put("tipo_da_licenca", "ST103")
                    .put("empresa", payload.empresaId)
                    .put("grupo_dispositivos", "PlaylistPadrão")
                    .put("campanhas", org.json.JSONArray())
                    .put("ip_dispositivo", "")
                    .put("num_filial", payload.numFilial)
                    .put("online", true)
                    .put("type", "ST103")

                val body = JSONObject().put("payload", inner).toString()
                BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val suffix = if (err.isBlank()) "" else " $err"
                    return@runCatching DeviceRegistrationResult.Error("http_$code$suffix")
                }

                DeviceRegistrationResult.Success
            }.getOrElse {
                DeviceRegistrationResult.Error(it.javaClass.simpleName)
            }.also {
                conn.disconnect()
            }
        }
}
