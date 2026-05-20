package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CompanyInfo(
    val id: String,
    val name: String,
    val tenantId: String,
)

sealed class CompanyLookupResult {
    data class Found(val company: CompanyInfo) : CompanyLookupResult()
    data object NotFound : CompanyLookupResult()
    data object NotConfigured : CompanyLookupResult()
    data class Error(val message: String) : CompanyLookupResult()
}

class CompanyLookupService(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    suspend fun lookupByCode(code: String): CompanyLookupResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext CompanyLookupResult.NotConfigured

        val safeCode = URLEncoder.encode(code.trim(), "UTF-8")
        val url = URL("${BuildConfig.SUPABASE_COMPANIES_URL}?select=*&code=eq.$safeCode")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("apikey", token)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Range", "0-0")
        }

        return@withContext runCatching {
            val codeHttp = conn.responseCode
            val stream = if (codeHttp in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codeHttp !in 200..299) return@runCatching CompanyLookupResult.Error("http_$codeHttp")

            val arr = JSONArray(responseText)
            if (arr.length() == 0) return@runCatching CompanyLookupResult.NotFound

            val obj = arr.getJSONObject(0)
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            val tenantId = obj.optString("tenant_id", "")
            if (id.isBlank() || tenantId.isBlank()) return@runCatching CompanyLookupResult.Error("invalid_response")

            CompanyLookupResult.Found(CompanyInfo(id = id, name = name, tenantId = tenantId))
        }.getOrElse {
            CompanyLookupResult.Error(it.javaClass.simpleName)
        }.also {
            conn.disconnect()
        }
    }
}

