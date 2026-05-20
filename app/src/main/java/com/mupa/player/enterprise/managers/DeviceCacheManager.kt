package com.mupa.player.enterprise.managers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

data class DeviceCache(
    val deviceDbId: Long,
    val deviceId: String,
    val deviceName: String,
    val filial: String,
    val company: String,
    val companyCode: String,
    val companyName: String,
    val tenant: String,
    val lastSyncEpochMs: Long,
    val deviceRegistered: Boolean,
)

class DeviceCacheManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val deviceDbId = longPreferencesKey("device_db_id")
        val deviceId = stringPreferencesKey("device_id")
        val deviceName = stringPreferencesKey("device_name")
        val filial = stringPreferencesKey("filial")
        val company = stringPreferencesKey("company")
        val companyCode = stringPreferencesKey("company_code")
        val companyName = stringPreferencesKey("company_name")
        val tenant = stringPreferencesKey("tenant")
        val lastSync = longPreferencesKey("ultimo_sync")
        val rawJson = stringPreferencesKey("device_cache_json")
        val deviceRegistered = booleanPreferencesKey("device_registered")
    }

    suspend fun save(cache: DeviceCache) {
        legacyPrefs.edit()
            .putLong("device_db_id", cache.deviceDbId)
            .putString("device_id", cache.deviceId)
            .putString("device_name", cache.deviceName)
            .putString("filial", cache.filial)
            .putString("company", cache.company)
            .putString("company_code", cache.companyCode)
            .putString("company_name", cache.companyName)
            .putString("tenant", cache.tenant)
            .putLong("ultimo_sync", cache.lastSyncEpochMs)
            .putBoolean("device_registered", cache.deviceRegistered)
            .apply()

        val json = JSONObject()
            .put("device_db_id", cache.deviceDbId)
            .put("device_id", cache.deviceId)
            .put("device_name", cache.deviceName)
            .put("filial", cache.filial)
            .put("company", cache.company)
            .put("company_code", cache.companyCode)
            .put("company_name", cache.companyName)
            .put("tenant", cache.tenant)
            .put("ultimo_sync", cache.lastSyncEpochMs)
            .put("device_registered", cache.deviceRegistered)
            .toString()

        context.settingsDataStore.edit { prefs ->
            prefs[Keys.deviceDbId] = cache.deviceDbId
            prefs[Keys.deviceId] = cache.deviceId
            prefs[Keys.deviceName] = cache.deviceName
            prefs[Keys.filial] = cache.filial
            prefs[Keys.company] = cache.company
            prefs[Keys.companyCode] = cache.companyCode
            prefs[Keys.companyName] = cache.companyName
            prefs[Keys.tenant] = cache.tenant
            prefs[Keys.lastSync] = cache.lastSyncEpochMs
            prefs[Keys.rawJson] = json
            prefs[Keys.deviceRegistered] = cache.deviceRegistered
        }
    }

    suspend fun load(): DeviceCache? {
        val prefs = context.settingsDataStore.data.first()
        val id = prefs[Keys.deviceId] ?: legacyPrefs.getString("device_id", null)
        if (id.isNullOrBlank()) return null

        return DeviceCache(
            deviceDbId = prefs[Keys.deviceDbId] ?: legacyPrefs.getLong("device_db_id", 0L),
            deviceId = id,
            deviceName = prefs[Keys.deviceName] ?: legacyPrefs.getString("device_name", "") ?: "",
            filial = prefs[Keys.filial] ?: legacyPrefs.getString("filial", "") ?: "",
            company = prefs[Keys.company] ?: legacyPrefs.getString("company", "") ?: "",
            companyCode = prefs[Keys.companyCode] ?: legacyPrefs.getString("company_code", "") ?: "",
            companyName = prefs[Keys.companyName] ?: legacyPrefs.getString("company_name", "") ?: "",
            tenant = prefs[Keys.tenant] ?: legacyPrefs.getString("tenant", "") ?: "",
            lastSyncEpochMs = prefs[Keys.lastSync] ?: legacyPrefs.getLong("ultimo_sync", 0L),
            deviceRegistered = prefs[Keys.deviceRegistered] ?: legacyPrefs.getBoolean("device_registered", false),
        )
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "mupa_device_cache_legacy"
    }
}
