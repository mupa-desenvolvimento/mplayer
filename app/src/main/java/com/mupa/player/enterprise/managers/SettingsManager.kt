package com.mupa.player.enterprise.managers

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class AppSettings(
    val serverUrl: String,
    val environment: String,
    val companyId: String,
    val tenantId: String,
    val deviceUuid: String,
    val devMode: Boolean,
)

class SettingsManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val environment = stringPreferencesKey("environment")
        val companyId = stringPreferencesKey("company_id")
        val tenantId = stringPreferencesKey("tenant_id")
        val deviceUuid = stringPreferencesKey("device_uuid")
        val devMode = booleanPreferencesKey("dev_mode")
    }

    val settingsFlow: Flow<AppSettings> =
        context.settingsDataStore.data
            .map { prefs ->
                val deviceUuid = prefs[Keys.deviceUuid]
                    ?: legacyPrefs.getString(LEGACY_KEY_DEVICE_UUID, null)
                    ?: ""

                AppSettings(
                    serverUrl = prefs[Keys.serverUrl]
                        ?: legacyPrefs.getString(LEGACY_KEY_SERVER_URL, DEFAULT_SERVER_URL)
                        ?: DEFAULT_SERVER_URL,
                    environment = prefs[Keys.environment]
                        ?: legacyPrefs.getString(LEGACY_KEY_ENVIRONMENT, DEFAULT_ENVIRONMENT)
                        ?: DEFAULT_ENVIRONMENT,
                    companyId = prefs[Keys.companyId]
                        ?: legacyPrefs.getString(LEGACY_KEY_COMPANY_ID, "")
                        ?: "",
                    tenantId = prefs[Keys.tenantId]
                        ?: legacyPrefs.getString(LEGACY_KEY_TENANT_ID, "")
                        ?: "",
                    deviceUuid = deviceUuid,
                    devMode = prefs[Keys.devMode]
                        ?: legacyPrefs.getBoolean(LEGACY_KEY_DEV_MODE, false),
                )
            }
            .distinctUntilChanged()

    suspend fun getSettings(): AppSettings = settingsFlow.first().let { current ->
        if (current.deviceUuid.isNotBlank()) {
            current
        } else {
            val uuid = getOrCreateDeviceUuid()
            current.copy(deviceUuid = uuid)
        }
    }

    suspend fun setServerUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        persistString(Keys.serverUrl, LEGACY_KEY_SERVER_URL, normalized)
    }

    suspend fun setEnvironment(value: String) {
        persistString(Keys.environment, LEGACY_KEY_ENVIRONMENT, value.trim())
    }

    suspend fun setCompanyId(value: String) {
        persistString(Keys.companyId, LEGACY_KEY_COMPANY_ID, value.trim())
    }

    suspend fun setTenantId(value: String) {
        persistString(Keys.tenantId, LEGACY_KEY_TENANT_ID, value.trim())
    }

    suspend fun setDevMode(enabled: Boolean) {
        persistBoolean(Keys.devMode, LEGACY_KEY_DEV_MODE, enabled)
    }

    suspend fun getOrCreateDeviceUuid(): String {
        val current = context.settingsDataStore.data.first()[Keys.deviceUuid]
            ?: legacyPrefs.getString(LEGACY_KEY_DEVICE_UUID, null)
        if (!current.isNullOrBlank()) return current

        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        val resolved =
            if (!androidId.isNullOrBlank() && androidId.lowercase() != ANDROID_ID_BUG) {
                androidId
            } else {
                UUID.randomUUID().toString()
            }

        persistString(Keys.deviceUuid, LEGACY_KEY_DEVICE_UUID, resolved)
        return resolved
    }

    fun getDeviceUuidCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_DEVICE_UUID, "") ?: ""
    }

    private suspend fun persistString(key: Preferences.Key<String>, legacyKey: String, value: String) {
        legacyPrefs.edit().putString(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun persistBoolean(
        key: Preferences.Key<Boolean>,
        legacyKey: String,
        value: Boolean,
    ) {
        legacyPrefs.edit().putBoolean(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    companion object {
        private const val DEFAULT_SERVER_URL = "https://midias.mupa.app"
        private const val DEFAULT_ENVIRONMENT = "prod"

        private const val LEGACY_PREFS_NAME = "mupa_settings_legacy"
        private const val LEGACY_KEY_SERVER_URL = "server_url"
        private const val LEGACY_KEY_ENVIRONMENT = "environment"
        private const val LEGACY_KEY_COMPANY_ID = "company_id"
        private const val LEGACY_KEY_TENANT_ID = "tenant_id"
        private const val LEGACY_KEY_DEVICE_UUID = "device_uuid"
        private const val LEGACY_KEY_DEV_MODE = "dev_mode"

        private const val ANDROID_ID_BUG = "9774d56d682e549c"
    }
}

