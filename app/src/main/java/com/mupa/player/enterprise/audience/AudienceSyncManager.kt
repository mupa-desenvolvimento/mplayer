package com.mupa.player.enterprise.audience

import android.content.Context
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AudienceSyncManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val http = OkHttpClient()

    suspend fun uploadPending(maxSessions: Int = 500): Boolean = withContext(Dispatchers.IO) {
        val sessions = db.audienceSessionDao().getPending(maxSessions)
        if (sessions.isEmpty()) return@withContext true

        val deviceId = sessions.first().deviceId

        val baseUrl = SettingsManager(context).getSettings().serverUrl.trim().trimEnd('/')
        val url = "$baseUrl/api/audience/upload"

        val sessionsArr = JSONArray()
        sessions.forEach { s ->
            sessionsArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("device", s.deviceId)
                    .put("face_hash", s.faceHash)
                    .put("first_seen", s.firstSeenEpochMs)
                    .put("last_seen", s.lastSeenEpochMs)
                    .put("view_duration_seconds", s.viewDurationSeconds)
                    .put("look_count", s.lookCount)
                    .put("estimated_age", s.estimatedAge)
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
                .put("device", deviceId)
                .put("created_at", System.currentTimeMillis())
                .put("sessions", sessionsArr)

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val ok = runCatching {
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (ok) {
            db.audienceSessionDao().markUploaded(sessions.map { it.id }, System.currentTimeMillis())
        }
        ok
    }
}
