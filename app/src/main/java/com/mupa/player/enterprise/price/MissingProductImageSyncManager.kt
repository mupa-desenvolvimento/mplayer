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

/**
 * Sobe em lote, para o Supabase, os EANs que ficaram sem imagem em nenhuma fonte
 * (`PriceQueryEngine.reportMissingImage`), para entrarem na fila de trabalho de um técnico.
 * Espelha exatamente o padrão de [PriceAnalyticsSyncManager].
 */
class MissingProductImageSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http = TlsCompat.newClient()

    suspend fun uploadPending(limit: Int = 200): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext false

        val pending = db.missingProductImageDao().getPending(limit)
        if (pending.isEmpty()) return@withContext true

        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/product_images_missing?on_conflict=ean"
        val arr = JSONArray()
        pending.forEach { e ->
            arr.put(
                JSONObject()
                    .put("ean", e.ean)
                    .put("company_id", e.companyId)
                    .put("device_id", e.deviceId)
                    .put("last_checked_at", java.time.Instant.ofEpochMilli(e.firstReportedAtEpochMs).toString()),
            )
        }

        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(arr.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val ok =
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.w(
                            "MPlayerPrice",
                            "missing_image_upload_failed code=${resp.code} body=${resp.body?.string()}",
                        )
                    }
                    resp.isSuccessful
                }
            }.onFailure {
                android.util.Log.w("MPlayerPrice", "missing_image_upload_exception err=${it.javaClass.simpleName}:${it.message}")
            }.getOrDefault(false)

        if (ok) {
            db.missingProductImageDao().markUploaded(pending.map { it.ean }, System.currentTimeMillis())
        }
        ok
    }
}
