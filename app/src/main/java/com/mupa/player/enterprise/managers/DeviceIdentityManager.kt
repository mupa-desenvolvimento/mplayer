package com.mupa.player.enterprise.managers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

class DeviceIdentityManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val persistentDeviceId = stringPreferencesKey(PERSISTENT_DEVICE_ID_TAG)
        val legacyPersistentDeviceId = stringPreferencesKey(LEGACY_DEVICE_ID_TAG)
    }

    suspend fun getPersistentId(): String = generateIfMissing()

    suspend fun generateIfMissing(): String {
        val current = load()
        val best = resolveBestAvailableId()
        if (current.isNullOrBlank()) {
            save(best)
            return best
        }
        if (best != current && validate(best)) {
            save(best)
            return best
        }
        return current
    }

    suspend fun save(deviceId: String) {
        val normalized = deviceId.trim()
        if (!validate(normalized)) return

        legacyPrefs.edit()
            .putString(PERSISTENT_DEVICE_ID_TAG, normalized)
            .putString(LEGACY_DEVICE_ID_TAG, normalized)
            .apply()
        context.settingsDataStore.edit {
            it[Keys.persistentDeviceId] = normalized
            it[Keys.legacyPersistentDeviceId] = normalized
        }
    }

    suspend fun load(): String? {
        val prefs = context.settingsDataStore.data.first()
        val fromDsNew = prefs[Keys.persistentDeviceId]
        if (!fromDsNew.isNullOrBlank()) return fromDsNew
        val fromDsLegacy = prefs[Keys.legacyPersistentDeviceId]
        if (!fromDsLegacy.isNullOrBlank()) return fromDsLegacy

        val fromPrefsNew = legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, null)
        if (!fromPrefsNew.isNullOrBlank()) return fromPrefsNew
        val fromPrefsLegacy = legacyPrefs.getString(LEGACY_DEVICE_ID_TAG, null)
        return fromPrefsLegacy?.takeIf { it.isNotBlank() }
    }

    fun getCachedId(): String {
        val fromNew = legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, "") ?: ""
        if (fromNew.isNotBlank()) return fromNew
        return legacyPrefs.getString(LEGACY_DEVICE_ID_TAG, "") ?: ""
    }

    fun validate(deviceId: String): Boolean = deviceId.isNotBlank() && deviceId.length >= 8

    private fun resolveBestAvailableId(): String {
        val stable = resolveStableHardwareId()
        if (!stable.isNullOrBlank()) return stable

        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        if (!androidId.isNullOrBlank() && androidId.lowercase() != ANDROID_ID_BUG) return androidId
        return ""
    }

    private fun resolveStableHardwareId(): String? {
        // O Argos (agent MDM) é Device Owner e consegue ler o serial real de hardware mesmo em
        // Android 10+, onde Build.getSerial() é bloqueado para apps comuns. Ele expõe esse valor
        // via ContentProvider para o MPlayer usar o mesmo device_id/serial entre os dois apps.
        val argosSerial = resolveSerialFromArgos()
        if (!argosSerial.isNullOrBlank()) return argosSerial

        val zebraId = resolveZebraStableId()
        if (!zebraId.isNullOrBlank()) return zebraId

        val serial = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }.getOrNull()?.trim().orEmpty()
        if (serial.isBlank()) return null
        if (serial.equals("unknown", ignoreCase = true)) return null
        if (!validate(serial)) return null
        return serial
    }

    private fun resolveSerialFromArgos(): String? {
        return runCatching {
            val uri = Uri.parse("content://$ARGOS_DEVICE_PROVIDER_AUTHORITY/serial")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex("serial")
                if (idx < 0) return@use null
                cursor.getString(idx)?.trim()?.takeIf { validate(it) }
            }
        }.getOrNull()
    }

    private fun resolveZebraStableId(): String? {
        val keys = listOf(
            "persist.vendor.zebra.sys.uuid",
            "ro.vendor.zebra.hardware.uuid",
            "ro.serialno",
            "ro.boot.serialno",
        )
        for (k in keys) {
            val v = readSystemPropertyCompat(k)?.trim().orEmpty()
            if (v.isBlank()) continue
            if (v.equals("unknown", ignoreCase = true)) continue
            if (!validate(v)) continue
            return v
        }
        return null
    }

    private fun readSystemPropertyCompat(key: String): String? {
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, "") as? String)?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    companion object {
        const val PERSISTENT_DEVICE_ID_TAG = "persistent_device_id"
        private const val LEGACY_DEVICE_ID_TAG = "device_id"

        private const val LEGACY_PREFS_NAME = "mupa_device_identity_legacy"
        private const val ANDROID_ID_BUG = "9774d56d682e549c"
        private const val ARGOS_DEVICE_PROVIDER_AUTHORITY = "com.mupa.agent.argos.provider.device"
    }
}
