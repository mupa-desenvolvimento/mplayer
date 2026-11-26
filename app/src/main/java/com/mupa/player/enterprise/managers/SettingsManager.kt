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
        val unidasulServerAddress = stringPreferencesKey("unidasul_server_address")
        val customQueryXml = stringPreferencesKey("custom_query_xml")
        val imageSearchEnabled = booleanPreferencesKey("image_search_enabled")
        val heartbeatIdleMinutes = intPreferencesKey("heartbeat_idle_minutes")
        val priceHost = stringPreferencesKey("price_host")
        val pricePort = stringPreferencesKey("price_port")
        val imageHost = stringPreferencesKey("image_host")
        val imagePort = stringPreferencesKey("image_port")
        val foreignCurrencyEnabled = booleanPreferencesKey("foreign_currency_enabled")
        val showUsdCurrency = booleanPreferencesKey("show_usd_currency")
        val showArsCurrency = booleanPreferencesKey("show_ars_currency")
        val adArtworkEnabled = booleanPreferencesKey("ad_artwork_enabled")
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

    // Importador manual do "XML da consulta" (Mupa Studio, botão "XML da consulta",
    // mupa-studio-query): cola/importa o XML inteiro aqui pra testar uma integração nova sem
    // precisar de código nativo — mesmo padrão de tc_server_address acima (blank = desativado,
    // tem prioridade sobre a empresa/manifesto). Ver PriceConfigXmlImporter.
    suspend fun setCustomQueryXml(value: String) {
        context.settingsDataStore.edit { it[Keys.customQueryXml] = value.trim() }
    }

    suspend fun getCustomQueryXml(): String {
        return context.settingsDataStore.data.first()[Keys.customQueryXml]?.trim().orEmpty()
    }

    // Host da API oficial UnidaSul.Api.TerminalConsulta da filial (ex:
    // "f002srv01.filial.unidasul.local") — mesmo padrão de tc_server_address acima
    // (blank = integração desativada). Trocou de bridge local scraped (/get_price,
    // reverse-engenheirado de App_TC_Unidasul.aia em 2026-10-13) pra API oficial em
    // 2026-11-11 — ver PriceQueryEngine.queryUnidasul e docs/FEATURE_INTEGRACAO_UNIDASUL.md.
    suspend fun setUnidasulServerAddress(value: String) {
        context.settingsDataStore.edit { it[Keys.unidasulServerAddress] = value.trim() }
    }

    // Conversão de moeda estrangeira via TCServer: duas linhas especiais cadastradas na mesma
    // tabela de produtos do TCServer (barcode literal "moeda_dolar"/"moeda_peso", "Preco1" =
    // quantos reais vale 1 unidade daquela moeda — ex: "moeda_dolar|Cotação Dólar|5,34|"). Ver
    // PriceQueryEngine.fetchCurrencyQuote. "Mostrar X" controla só a exibição; a busca da
    // cotação só acontece se a chave mestre (foreignCurrencyEnabled) estiver ligada.
    suspend fun setForeignCurrencyEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.foreignCurrencyEnabled] = enabled }
    }

    suspend fun getForeignCurrencyEnabled(): Boolean {
        return context.settingsDataStore.data.first()[Keys.foreignCurrencyEnabled] ?: false
    }

    suspend fun setShowUsdCurrency(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showUsdCurrency] = enabled }
    }

    suspend fun getShowUsdCurrency(): Boolean {
        return context.settingsDataStore.data.first()[Keys.showUsdCurrency] ?: true
    }

    suspend fun setShowArsCurrency(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showArsCurrency] = enabled }
    }

    suspend fun getShowArsCurrency(): Boolean {
        return context.settingsDataStore.data.first()[Keys.showArsCurrency] ?: true
    }

    // Modo Publicidade das Imagens (pedido do usuário, 2026-11-17): controla só o passo 0 de
    // PriceQueryEngine.preloadProductImageAndTheme — a arte publicitária gerada por IA
    // (produtos-imgs/Gemini, srv-mupa), que vira o fundo da tela toda. Desligado, pula direto
    // pra foto crua do produto (passos 1-3), sem a camada de "peça de campanha". Hoje é
    // configurado por dispositivo; a ideia é que no futuro venha do Mupa Connect por empresa.
    // Default true: preserva o comportamento atual (busca de arte sempre ligada) pra toda a
    // frota existente — só quem desligar explicitamente perde a arte.
    suspend fun setAdArtworkEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.adArtworkEnabled] = enabled }
    }

    suspend fun getAdArtworkEnabled(): Boolean {
        return context.settingsDataStore.data.first()[Keys.adArtworkEnabled] ?: true
    }

    suspend fun getUnidasulServerAddress(): String {
        return context.settingsDataStore.data.first()[Keys.unidasulServerAddress]?.trim().orEmpty()
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
        // Achado real (2026-10-21): "srv-mupa.ddns.net" resolve pra uma máquina física
        // diferente (177.10.108.33) do servidor atual do produtos-imgs/Mupa Brain. Essa
        // máquina antiga só redireciona GET pra "2.25.239.153:5050" (302) — POST (usado por
        // gerar-arte, upload de foto) nunca é encaminhado, cai direto num 405 do lado de lá.
        // Servidor de verdade hoje é esta própria VPS do argos-remote, mesma porta 5050.
        private const val DEFAULT_IMAGE_HOST = "2.25.239.153"
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

