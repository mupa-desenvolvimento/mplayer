package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

class DeviceIdentityManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val persistentDeviceId = stringPreferencesKey(PERSISTENT_DEVICE_ID_TAG)
    }

    suspend fun getPersistentId(): String = generateIfMissing()

    suspend fun generateIfMissing(): String {
        val current = load()
        if (!current.isNullOrBlank()) return current

        val generated = resolveBestAvailableId()
        save(generated)
        return generated
    }

    suspend fun save(deviceId: String) {
        val normalized = deviceId.trim()
        if (!validate(normalized)) return

        legacyPrefs.edit().putString(PERSISTENT_DEVICE_ID_TAG, normalized).apply()
        context.settingsDataStore.edit { it[Keys.persistentDeviceId] = normalized }
    }

    suspend fun load(): String? {
        val fromDs = context.settingsDataStore.data.first()[Keys.persistentDeviceId]
        if (!fromDs.isNullOrBlank()) return fromDs
        val fromPrefs = legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, null)
        return fromPrefs?.takeIf { it.isNotBlank() }
    }

    fun getCachedId(): String {
        return legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, "") ?: ""
    }

    fun validate(deviceId: String): Boolean = deviceId.isNotBlank() && deviceId.length >= 8

    private fun resolveBestAvailableId(): String {
        val serial = resolveHardwareSerial()
        if (!serial.isNullOrBlank()) return serial

        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        if (!androidId.isNullOrBlank() && androidId.lowercase() != ANDROID_ID_BUG) return androidId
        return UUID.randomUUID().toString()
    }

    private fun resolveHardwareSerial(): String? {
        val serial = runCatching {
            when {
                Build.VERSION.SDK_INT >= 26 -> Build.getSerial()
                else -> Build.SERIAL
            }
        }.getOrNull()?.trim().orEmpty()

        if (serial.isBlank()) return null
        if (serial.equals("unknown", ignoreCase = true)) return null
        return serial
    }

    companion object {
        const val PERSISTENT_DEVICE_ID_TAG = "persistent_device_id"

        private const val LEGACY_PREFS_NAME = "mupa_device_identity_legacy"
        private const val ANDROID_ID_BUG = "9774d56d682e549c"
    }
}
