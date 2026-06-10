package com.mupa.player.enterprise.price

import org.json.JSONArray
import org.json.JSONObject

data class PriceConfig(
    val integration: String,
    val timeoutMs: Long,
    val cacheMinutes: Int,
    val steps: List<PriceStep>,
    val analyticsUploadUrl: String?,
)

data class PriceStep(
    val type: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val mapping: Map<String, String>,
    val body: String? = null,
)

object PriceConfigParser {
    fun parse(json: String): PriceConfig? {
        val root = JSONObject(json)
        val integration = root.optString("integration", "").trim()
        if (integration.isBlank()) return null

        val timeout = root.optLong("timeout", 8000L).coerceAtLeast(1000L)
        val cacheMin = root.optInt("cache_minutes", 30).coerceAtLeast(0)
        val uploadUrl = root.optString("analytics_upload_url", "").trim().ifBlank { null }

        val stepsArr = root.optJSONArray("steps") ?: JSONArray()
        val steps = ArrayList<PriceStep>(stepsArr.length())
        for (i in 0 until stepsArr.length()) {
            val o = stepsArr.optJSONObject(i) ?: continue
            val type = o.optString("type", "").trim()
            val url = o.optString("url", "").trim()
            if (type.isBlank() || url.isBlank()) continue

            val method = o.optString("method", "GET").trim().uppercase()
            val headersObj = o.optJSONObject("headers") ?: JSONObject()
            val headers = mutableMapOf<String, String>()
            headersObj.keys().forEach { k ->
                headers[k] = headersObj.optString(k, "")
            }

            val mappingObj = o.optJSONObject("mapping") ?: JSONObject()
            val mapping = mutableMapOf<String, String>()
            mappingObj.keys().forEach { k ->
                mapping[k] = mappingObj.optString(k, "")
            }

            val bodyObj = o.opt("body")
            val body = when (bodyObj) {
                is JSONObject -> bodyObj.toString()
                is JSONArray -> bodyObj.toString()
                is String -> bodyObj.trim()
                else -> null
            }

            steps += PriceStep(
                type = type,
                url = url,
                method = method,
                headers = headers,
                mapping = mapping,
                body = body,
            )
        }

        if (steps.isEmpty()) return null
        return PriceConfig(
            integration = integration,
            timeoutMs = timeout,
            cacheMinutes = cacheMin,
            steps = steps,
            analyticsUploadUrl = uploadUrl,
        )
    }
}

