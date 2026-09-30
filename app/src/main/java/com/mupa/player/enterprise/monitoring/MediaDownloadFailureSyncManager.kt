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
 * Envia em lote para o Supabase (tabela `media_download_failures`) os registros de falha
 * no download de mídias gravados localmente pelo [com.mupa.player.enterprise.managers.ManifestManager].
 */
class MediaDownloadFailureSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http: OkHttpClient = TlsCompat.newClient()

    suspend fun uploadPending(limit: Int = 100): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext false

        val pending = db.mediaDownloadFailureDao().getPending(limit)
        if (pending.isEmpty()) return@withContext true

        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/media_download_failures"
        val arr = JSONArray()
        pending.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("device_id", e.deviceId)
                    .put("media_id", e.mediaId)
                    .put("media_name", e.mediaName)
                    .put("url", e.url)
                    .put("error_reason", e.errorReason)
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
            db.mediaDownloadFailureDao().markUploaded(pending.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
