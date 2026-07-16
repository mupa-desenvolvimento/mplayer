package com.mupa.player.enterprise.player

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MediaPlayLogsSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http = TlsCompat.newClient()

    suspend fun uploadPending(limit: Int = 500): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext false

        val pending = db.mediaPlayLogDao().getPending(limit)
        if (pending.isEmpty()) return@withContext true

        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/media_play_logs"
        val arr = JSONArray()
        
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        pending.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("device_id", e.deviceId)
                    .put("media_id", e.mediaId)
                    .put("duration", e.durationSeconds)
                    .put("played_at", df.format(Date(e.playedAtEpochMs))),
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
            db.mediaPlayLogDao().markUploaded(pending.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
