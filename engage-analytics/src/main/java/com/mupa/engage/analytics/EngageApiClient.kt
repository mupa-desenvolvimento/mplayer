package com.mupa.engage.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class EngageApiClient(
    private val okHttp: OkHttpClient = OkHttpClient(),
) {
    suspend fun fetchQuestions(url: String): List<EngageQuestion> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        okHttp.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val body = res.body?.string().orEmpty().trim()
            if (body.isBlank()) return@withContext emptyList()
            parseQuestions(body)
        }
    }

    suspend fun postAnalytics(url: String, event: EngageAnalyticsEvent): Boolean = withContext(Dispatchers.IO) {
        val json = event.toJson().toString()
        val req =
            Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        okHttp.newCall(req).execute().use { res -> res.isSuccessful }
    }

    private fun parseQuestions(json: String): List<EngageQuestion> {
        val root = runCatching { JSONObject(json) }.getOrNull()
        val arr =
            when {
                root != null && root.has("data") -> root.optJSONArray("data")
                root != null && root.has("questions") -> root.optJSONArray("questions")
                else -> null
            } ?: runCatching { JSONArray(json) }.getOrNull()
            ?: JSONArray()

        val out = ArrayList<EngageQuestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", -1L)
            val pergunta = o.optString("pergunta", "").trim()
            val opcao1 = o.optString("opcao1", "").trim()
            val opcao2 = o.optString("opcao2", "").trim()
            if (id <= 0L || pergunta.isBlank() || opcao1.isBlank() || opcao2.isBlank()) continue
            out +=
                EngageQuestion(
                    id = id,
                    categoria = o.optString("categoria", "").trim().ifBlank { null },
                    publico = o.optString("publico", "").trim().ifBlank { null },
                    pergunta = pergunta,
                    opcao1 = opcao1,
                    opcao2 = opcao2,
                )
        }
        return out
    }
}

