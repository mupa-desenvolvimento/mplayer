package com.mupa.player.enterprise.monitoring

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sobe em lote, para o Supabase (tabela `device_events`), os eventos de monitoramento
 * (heartbeat, falha repetida do mesmo item, retomada de conectividade) gravados localmente
 * por [PlayerActivity] — ver `MUPA_PLATFORM_DEVICE_EVENTS_CONTRACT.md`. Mesmo padrão de
 * [com.mupa.player.enterprise.price.PriceAnalyticsSyncManager].
 */
class DeviceEventSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http: OkHttpClient = TlsCompat.newClient()

    suspend fun uploadPending(limit: Int = 200): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext false

        val pending = db.deviceEventDao().getPending(limit)
        if (pending.isEmpty()) return@withContext true

        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/device_events"
        val arr = JSONArray()
        pending.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("device_id", e.deviceId)
                    .put("company_id", e.companyId)
                    .put("filial", e.filial)
                    .put("event_type", e.eventType)
                    .put("reason", e.reason)
                    .put("ean", e.ean)
                    .put("fail_count", e.failCount)
                    .put("offline_duration_seconds", e.offlineDurationSeconds)
                    .put("created_at_epoch_ms", e.createdAtEpochMs),
            )
        }

        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(arr.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val ok =
            runCatching {
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)

        if (ok) {
            db.deviceEventDao().markUploaded(pending.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
