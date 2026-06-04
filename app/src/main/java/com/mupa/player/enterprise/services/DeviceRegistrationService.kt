package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val api = SupabaseClient.createApi()

    suspend fun register(payload: DeviceRegistrationPayload): DeviceRegistrationResult =
        withContext(Dispatchers.IO) {
            val token = BuildConfig.SUPABASE_TOKEN.trim()
            if (token.isBlank()) return@withContext DeviceRegistrationResult.NotConfigured

            return@withContext runCatching {
                api.postJson(
                    url = BuildConfig.SUPABASE_CREATE_DEVICE_RPC_URL,
                    body = mapOf(
                        "payload" to mapOf(
                            "apelido_interno" to payload.apelidoInterno,
                            "apps_instalados" to emptyList<Any>(),
                            "pin" to payload.empresaCode,
                            "serial" to payload.serial,
                            "tipo_da_licenca" to "ST103",
                            "empresa" to payload.empresaId,
                            "grupo_dispositivos" to "PlaylistPadrão",
                            "campanhas" to emptyList<Any>(),
                            "ip_dispositivo" to "",
                            "num_filial" to payload.numFilial,
                            "online" to true,
                            "type" to "ST103",
                        ),
                    ),
                ).close()

                DeviceRegistrationResult.Success
            }.getOrElse {
                DeviceRegistrationResult.Error(it.javaClass.simpleName)
            }
        }
}
