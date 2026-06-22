package com.mupa.player.enterprise.audience

import android.content.Context
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.TlsCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.mupa.player.enterprise.utils.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject

class AudienceSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http = TlsCompat.newClient()

    suspend fun uploadPending(maxSessions: Int = 500): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        android.util.Log.d("AudienceSyncManager", "SUPABASE_TOKEN is blank: ${token.isBlank()} length: ${token.length}")
        if (token.isBlank()) return@withContext false

        val sessions = db.audienceSessionDao().getPending(maxSessions)
        android.util.Log.d("AudienceSyncManager", "Found ${sessions.size} pending sessions")
        if (sessions.isEmpty()) return@withContext true

        val deviceId = sessions.first().deviceId
        val url = "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/audience-analytics"

        val sessionsArr = JSONArray()
        sessions.forEach { s ->
            sessionsArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("device", CryptoUtils.encrypt(s.deviceId))
                    .put("first_seen", s.firstSeenEpochMs)
                    .put("last_seen", s.lastSeenEpochMs)
                    .put("view_duration_seconds", s.viewDurationSeconds)
                    .put("look_count", s.lookCount)
                    .put("age_range", s.ageRange)
                    .put("gender", s.gender)
                    .put("confidence", s.confidence)
                    .put("hour", s.hour)
                    .put("weekday", s.weekday)
                    .put("content_playing", s.contentPlaying)
                    .put("playlist", s.playlist),
            )
        }

        val payload =
            JSONObject()
                .put("device", CryptoUtils.encrypt(deviceId))
                .put("created_at", System.currentTimeMillis())
                .put("sessions", sessionsArr)

        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val ok = runCatching {
            http.newCall(req).execute().use { resp ->
                val success = resp.isSuccessful
                if (!success) {
                    val body = resp.body?.string() ?: ""
                    android.util.Log.e("AudienceSyncManager", "Upload failed code=${resp.code} body=$body")
                }
                success
            }
        }.onFailure {
            android.util.Log.e("AudienceSyncManager", "Upload exception", it)
        }.getOrDefault(false)

        if (ok) {
            db.audienceSessionDao().markUploaded(sessions.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
