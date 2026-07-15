package com.mupa.player.enterprise.price

import org.json.JSONArray
import org.json.JSONObject

data class PriceConfig(
    val integration: String,
    val timeoutMs: Long,
    val cacheMinutes: Int,
    val steps: List<PriceStep>,
    val analyticsUploadUrl: String?,
    val layout: LayoutConfig? = null,
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
        val cacheMin = 10
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

        val allowEmptySteps = integration == "integra-assai" || integration == "integra-americana" || integration == "integra-zaffari"
        if (steps.isEmpty() && !allowEmptySteps) return null

        val layoutObj = root.optJSONObject("layout")
        val layout = if (layoutObj != null) {
            val style = layoutObj.optString("style", "default")
            val xmlLayoutType = layoutObj.optString("xml_layout_type", "split")
            val colorMode = layoutObj.optString("color_mode", "auto")
            val solidColor = layoutObj.optString("solid_color", "#06b6d4")
            val slotsArr = layoutObj.optJSONArray("price_slots")
            val slots = ArrayList<PriceSlot>()
            if (slotsArr != null) {
                for (j in 0 until slotsArr.length()) {
                    val sObj = slotsArr.optJSONObject(j) ?: continue
                    val field = sObj.optString("field", "")
                    val label = sObj.optString("label", "")
                    val showFromPrice = sObj.optBoolean("show_from_price", false)
                    if (field.isNotBlank()) {
                        slots += PriceSlot(field = field, label = label, showFromPrice = showFromPrice)
                    }
                }
            }
            LayoutConfig(
                style = style,
                xmlLayoutType = xmlLayoutType,
                colorMode = colorMode,
                solidColor = solidColor,
                priceSlots = slots
            )
        } else {
            null
        }

        return PriceConfig(
            integration = integration,
            timeoutMs = timeout,
            cacheMinutes = cacheMin,
            steps = steps,
            analyticsUploadUrl = uploadUrl,
            layout = layout,
        )
    }
}

