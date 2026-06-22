package com.mupa.player.enterprise.audience

import com.mupa.player.enterprise.storage.db.AudienceSessionEntity
import java.util.UUID
import java.util.Calendar
import java.util.Locale

class ViewingSessionTracker(
    private val deviceId: String,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Live(
        val id: String,
        val faceHash: String,
        val startedAtEpochMs: Long,
        var lastSeenEpochMs: Long,
        var lookCount: Int,
        var estimatedAge: Int?,
        var ageRange: String?,
        var gender: String?,
        var confidence: Float?,
        var contentPlaying: String?,
        var playlist: String?,
        var embedding: FloatArray? = null,
        var attentionDurationSeconds: Long = 0L,
    )

    private val sessionsByFace = HashMap<String, Live>()

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0.0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return Math.sqrt(sum.toDouble()).toFloat()
    }

    fun onFrame(
        frame: AudienceFrameResult,
        contentPlaying: String?,
        playlist: String?,
        expireAfterMs: Long,
    ): List<AudienceSessionEntity> {
        val now = nowEpochMs()

        val seen = HashSet<String>()
        for (face in frame.faces) {
            var live: Live? = null
            val faceEmbedding = face.embedding

            if (faceEmbedding != null) {
                var bestDistance = Float.MAX_VALUE
                var bestLive: Live? = null
                for (s in sessionsByFace.values) {
                    val sEmbedding = s.embedding
                    if (sEmbedding != null) {
                        val dist = euclideanDistance(faceEmbedding, sEmbedding)
                        if (dist < 1.25f && dist < bestDistance) {
                            bestDistance = dist
                            bestLive = s
                        }
                    }
                }
                live = bestLive
            }

            if (live == null) {
                live = sessionsByFace[face.faceHash]
            }

            val hash = live?.faceHash ?: face.faceHash
            seen += hash

            if (live == null) {
                val newLive = Live(
                    id = UUID.randomUUID().toString(),
                    faceHash = hash,
                    startedAtEpochMs = now,
                    lastSeenEpochMs = now,
                    lookCount = if (face.isLooking) 1 else 0,
                    estimatedAge = face.estimatedAge,
                    ageRange = face.ageRange,
                    gender = face.gender,
                    confidence = face.confidence,
                    contentPlaying = contentPlaying,
                    playlist = playlist,
                    embedding = faceEmbedding,
                    attentionDurationSeconds = face.attentionDurationSeconds,
                )
                sessionsByFace[hash] = newLive
            } else {
                live.lastSeenEpochMs = now
                if (face.isLooking) live.lookCount += 1
                if (live.estimatedAge == null && face.estimatedAge != null) live.estimatedAge = face.estimatedAge
                if (live.ageRange == null && face.ageRange != null) live.ageRange = face.ageRange
                if (live.gender == null && face.gender != null) live.gender = face.gender
                if (live.confidence == null && face.confidence != null) live.confidence = face.confidence
                live.contentPlaying = contentPlaying ?: live.contentPlaying
                live.playlist = playlist ?: live.playlist
                if (faceEmbedding != null) {
                    live.embedding = faceEmbedding
                }
                live.attentionDurationSeconds = face.attentionDurationSeconds
            }
        }

        val ended = ArrayList<AudienceSessionEntity>()
        val it = sessionsByFace.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val live = entry.value
            val inactiveMs = now - live.lastSeenEpochMs
            if (inactiveMs >= expireAfterMs && !seen.contains(entry.key)) {
                ended += live.toEntity()
                it.remove()
            }
        }

        return ended
    }

    fun flushAll(): List<AudienceSessionEntity> {
        val out = sessionsByFace.values.map { it.toEntity() }
        sessionsByFace.clear()
        return out
    }

    private fun Live.toEntity(): AudienceSessionEntity {
        val durationSeconds = attentionDurationSeconds.coerceAtLeast(0L)
        val cal = Calendar.getInstance().apply { timeInMillis = startedAtEpochMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase(Locale.US) ?: "unknown"
        return AudienceSessionEntity(
            id = id,
            deviceId = deviceId,
            faceHash = faceHash,
            firstSeenEpochMs = startedAtEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            viewDurationSeconds = durationSeconds,
            lookCount = lookCount,
            estimatedAge = null,
            ageRange = ageRange,
            gender = gender,
            confidence = confidence,
            hour = hour,
            weekday = weekday,
            contentPlaying = contentPlaying,
            playlist = playlist,
            uploadedAtEpochMs = null,
        )
    }
}
