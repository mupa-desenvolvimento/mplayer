package com.mupa.player.enterprise.managers

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val demoMode: Boolean,
    val imageSearchEnabled: Boolean,
    // Endpoint do serviço local de consulta de preços da loja (ex: 192.168.6.171:8000).
    // Interpolado no price_config como {{price_host}}/{{price_port}}; vazio = integração
    // não usa servidor local.
    val priceHost: String,
    val pricePort: String,
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
        val demoMode = booleanPreferencesKey("demo_mode")
        val tcServerAddress = stringPreferencesKey("tc_server_address")
        val gertecScannerEnabled = booleanPreferencesKey("gertec_scanner_enabled")
        val imageSearchEnabled = booleanPreferencesKey("image_search_enabled")
        val heartbeatIdleMinutes = intPreferencesKey("heartbeat_idle_minutes")
        val priceHost = stringPreferencesKey("price_host")
        val pricePort = stringPreferencesKey("price_port")
        val imageHost = stringPreferencesKey("image_host")
        val imagePort = stringPreferencesKey("image_port")
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
                    demoMode = prefs[Keys.demoMode]
                        ?: legacyPrefs.getBoolean(LEGACY_KEY_DEMO_MODE, false),
                    imageSearchEnabled = prefs[Keys.imageSearchEnabled]
                        ?: legacyPrefs.getBoolean(LEGACY_KEY_IMAGE_SEARCH_ENABLED, true),
                    priceHost = prefs[Keys.priceHost]
                        ?: legacyPrefs.getString(LEGACY_KEY_PRICE_HOST, "")
                        ?: "",
                    pricePort = prefs[Keys.pricePort]
                        ?: legacyPrefs.getString(LEGACY_KEY_PRICE_PORT, DEFAULT_PRICE_PORT)
                        ?: DEFAULT_PRICE_PORT,
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

    suspend fun setPriceHost(value: String) {
        persistString(Keys.priceHost, LEGACY_KEY_PRICE_HOST, value.trim())
    }

    suspend fun setPricePort(value: String) {
        persistString(Keys.pricePort, LEGACY_KEY_PRICE_PORT, value.trim())
    }

    suspend fun setDevMode(enabled: Boolean) {
        persistBoolean(Keys.devMode, LEGACY_KEY_DEV_MODE, enabled)
    }

    suspend fun setDemoMode(enabled: Boolean) {
        persistBoolean(Keys.demoMode, LEGACY_KEY_DEMO_MODE, enabled)
    }

    suspend fun setTcServerAddress(value: String) {
        context.settingsDataStore.edit { it[Keys.tcServerAddress] = value.trim() }
    }

    suspend fun getTcServerAddress(): String {
        return context.settingsDataStore.data.first()[Keys.tcServerAddress]?.trim().orEmpty()
    }

    suspend fun setImageHost(value: String) {
        persistString(Keys.imageHost, LEGACY_KEY_IMAGE_HOST, value.trim())
    }

    suspend fun getImageHost(): String {
        return context.settingsDataStore.data.first()[Keys.imageHost]?.trim()
            ?: legacyPrefs.getString(LEGACY_KEY_IMAGE_HOST, "") ?: ""
    }

    suspend fun setImagePort(value: String) {
        persistString(Keys.imagePort, LEGACY_KEY_IMAGE_PORT, value.trim())
    }

    suspend fun getImagePort(): String {
        return context.settingsDataStore.data.first()[Keys.imagePort]?.trim()
            ?: legacyPrefs.getString(LEGACY_KEY_IMAGE_PORT, "") ?: ""
    }

    /** Base URL do servidor produtos-imgs (ex.: http://192.168.6.219:5050 pra testar num
     * servidor de desenvolvimento, ou o srv-mupa de produção por padrão). */
    suspend fun getImageServerBaseUrl(): String {
        val host = getImageHost().ifBlank { DEFAULT_IMAGE_HOST }
        val port = getImagePort().ifBlank { DEFAULT_IMAGE_PORT }
        return "http://$host:$port"
    }

    suspend fun setGertecScannerEnabled(enabled: Boolean) {
        // Persiste no DataStore E no SharedPreferences (legacyPrefs). O DataStore pode ser
        // resetado por corrupção em reboot abrupto (ReplaceFileCorruptionHandler -> vazio); o
        // fallback no SharedPreferences garante que o leitor continue ativado após o boot.
        persistBoolean(Keys.gertecScannerEnabled, LEGACY_KEY_GERTEC_SCANNER_ENABLED, enabled)
    }

    // Achado real de campo (2026-10-08, MEFERI MC45 DER4BT125115002178):
    // o default `true` aqui era INCONDICIONAL (todo aparelho, Gertec ou
    // não), e o chamador (PlayerActivity.onResume) usa
    // `GertecScannerManager.isGertecDevice() || getGertecScannerEnabled()`
    // — como o lado direito do OR já vinha `true` por padrão em QUALQUER
    // device, a checagem de `isGertecDevice()` nunca tinha efeito nenhum
    // na prática. Resultado confirmado ao vivo via `adb logcat`: neste
    // MC45 (sem hardware Gertec nenhum), `GertecScannerManager` ficava
    // tentando se armar pra sempre, falhando a cada ~30s
    // ("gertec_sdk_getInstance_failed"), gastando CPU/wakeups à toa num
    // loop que nunca teria como funcionar. Fix: o default só vira `true`
    // quando o aparelho É de fato Gertec (`isGertecDevice()`) — em
    // qualquer outro, default `false`, preservando o comportamento
    // "ativo sem configuração manual" só onde faz sentido. Um admin que
    // precisar ligar manualmente num device fora da heurística ainda
    // pode, via Configurações — só o DEFAULT mudou.
    suspend fun getGertecScannerEnabled(): Boolean {
        val default = GertecScannerManager.isGertecDevice()
        return context.settingsDataStore.data.first()[Keys.gertecScannerEnabled]
            ?: legacyPrefs.getBoolean(LEGACY_KEY_GERTEC_SCANNER_ENABLED, default)
    }

    suspend fun setImageSearchEnabled(enabled: Boolean) {
        persistBoolean(Keys.imageSearchEnabled, LEGACY_KEY_IMAGE_SEARCH_ENABLED, enabled)
    }

    /** Minutos de ociosidade (sem nenhum scan) antes de enviar um heartbeat. Faixa 30–120min. */
    suspend fun setHeartbeatIdleMinutes(value: Int) {
        val clamped = value.coerceIn(MIN_HEARTBEAT_IDLE_MINUTES, MAX_HEARTBEAT_IDLE_MINUTES)
        context.settingsDataStore.edit { it[Keys.heartbeatIdleMinutes] = clamped }
    }

    suspend fun getHeartbeatIdleMinutes(): Int {
        return context.settingsDataStore.data.first()[Keys.heartbeatIdleMinutes] ?: DEFAULT_HEARTBEAT_IDLE_MINUTES
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
        private const val DEFAULT_PRICE_PORT = "8000"
        private const val DEFAULT_IMAGE_HOST = "srv-mupa.ddns.net"
        private const val DEFAULT_IMAGE_PORT = "5050"

        private const val LEGACY_PREFS_NAME = "mupa_settings_legacy"
        private const val LEGACY_KEY_SERVER_URL = "server_url"
        private const val LEGACY_KEY_ENVIRONMENT = "environment"
        private const val LEGACY_KEY_COMPANY_ID = "company_id"
        private const val LEGACY_KEY_TENANT_ID = "tenant_id"
        private const val LEGACY_KEY_DEVICE_UUID = "device_uuid"
        private const val LEGACY_KEY_DEV_MODE = "dev_mode"
        private const val LEGACY_KEY_DEMO_MODE = "demo_mode"
        private const val LEGACY_KEY_IMAGE_SEARCH_ENABLED = "image_search_enabled"
        private const val LEGACY_KEY_GERTEC_SCANNER_ENABLED = "gertec_scanner_enabled"
        private const val LEGACY_KEY_PRICE_HOST = "price_host"
        private const val LEGACY_KEY_PRICE_PORT = "price_port"
        private const val LEGACY_KEY_IMAGE_HOST = "image_host"
        private const val LEGACY_KEY_IMAGE_PORT = "image_port"

        private const val ANDROID_ID_BUG = "9774d56d682e549c"

        const val DEFAULT_HEARTBEAT_IDLE_MINUTES = 45
        const val MIN_HEARTBEAT_IDLE_MINUTES = 30
        const val MAX_HEARTBEAT_IDLE_MINUTES = 120
    }
}

