package com.mupa.player.enterprise.services

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

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
    private val api = SupabaseClient.createApi()

    suspend fun lookupByCode(code: String): CompanyLookupResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext CompanyLookupResult.NotConfigured

        return@withContext runCatching {
            val responseText = api
                .getCompaniesByCode(
                    url = BuildConfig.SUPABASE_COMPANIES_URL,
                    codeEq = "eq.${code.trim()}",
                )
                .string()

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
        }
    }
}
