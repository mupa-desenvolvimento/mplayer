package com.mupa.player.enterprise.price

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

class PriceAnalyticsSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http = TlsCompat.newClient()

    suspend fun uploadPending(limit: Int = 500): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext false

        val pending = db.priceQueryEventDao().getPending(limit)
        if (pending.isEmpty()) return@withContext true

        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/price_query_events"
        val arr = JSONArray()
        pending.forEach { e ->
            val obj = JSONObject()
                .put("id", e.id)
                .put("device_id", e.deviceId)
                .put("filial", e.filial)
                .put("ean", e.ean)
                .put("created_at_epoch_ms", e.createdAtEpochMs)
                .put("response_time_ms", e.responseTimeMs)
                .put("from_cache", e.fromCache)
                .put("success", e.success)
            if (!e.descricao.isNullOrBlank()) {
                obj.put("descricao", e.descricao)
            }
            arr.put(obj)
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
            db.priceQueryEventDao().markUploaded(pending.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
