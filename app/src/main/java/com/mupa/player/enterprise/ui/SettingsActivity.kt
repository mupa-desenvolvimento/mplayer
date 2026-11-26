package com.mupa.player.enterprise.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.databinding.ActivitySettingsBinding
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestFetchResult
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.price.PriceConfig
import com.mupa.player.enterprise.price.PriceConfigParser
import com.mupa.player.enterprise.price.PriceConfigXmlImporter
import com.mupa.player.enterprise.price.PriceQueryEngine
import com.mupa.player.enterprise.price.unidasulBuildUrl
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.settingsDataStore
import com.mupa.player.enterprise.audience.AudienceSyncManager
import com.mupa.player.enterprise.price.PriceAnalyticsSyncManager
import com.mupa.player.enterprise.price.MissingProductImageSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsActivity : ComponentActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var deviceId: String
    private var priceConfig: PriceConfig? = null
    private var maintenanceModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupCollapsibleCards()

        lifecycleScope.launch {
            deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
            loadDeviceInformation()
            setupListeners()
        }
    }

    // Pedido do usuário (2026-11-13): tela de Configurações minimizada por padrão, com um
    // ícone pra expandir cada seção — tela tinha ~12 cards sempre abertos ao mesmo tempo,
    // exigindo muito scroll pra achar o campo certo. Genérico/baseado em código em vez de
    // reescrever os 12 blocos de activity_settings.xml à mão: acha todo MaterialCardView da
    // tela (não precisam de nenhum id/atributo novo no XML), e em cada um assume o padrão já
    // usado em TODOS os cards hoje (1º filho do LinearLayout interno = título; o resto = corpo
    // a esconder). Cards com estrutura fora desse padrão são ignorados (nunca quebram a tela).
    private fun setupCollapsibleCards() {
        val cards = mutableListOf<MaterialCardView>()
        fun collectCards(view: View) {
            if (view is MaterialCardView) {
                cards.add(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collectCards(view.getChildAt(i))
            }
        }
        collectCards(binding.root)

        for (card in cards) {
            val content = (card.getChildAt(0) as? ViewGroup) ?: continue
            if (content.childCount < 2) continue
            val header = content.getChildAt(0) as? TextView ?: continue
            val bodyViews = (1 until content.childCount).map { content.getChildAt(it) }

            bodyViews.forEach { it.visibility = View.GONE }
            header.setCompoundDrawablesWithIntrinsicBounds(0, 0, com.mupa.player.enterprise.R.drawable.ic_chevron_down, 0)
            header.compoundDrawablePadding = 16
            header.isClickable = true
            header.isFocusable = true

            header.setOnClickListener {
                val expanding = bodyViews.firstOrNull()?.visibility != View.VISIBLE
                bodyViews.forEach { v -> v.visibility = if (expanding) View.VISIBLE else View.GONE }
                val chevron = if (expanding) com.mupa.player.enterprise.R.drawable.ic_chevron_up else com.mupa.player.enterprise.R.drawable.ic_chevron_down
                header.setCompoundDrawablesWithIntrinsicBounds(0, 0, chevron, 0)
            }
        }
    }

    private suspend fun loadDeviceInformation() {
        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        val settings = runCatching { SettingsManager(applicationContext).getSettings() }.getOrNull()

        // Local manifest parsing to fetch playlist name and price config
        val rawManifest = runCatching { ManifestManager(applicationContext).loadOfflineManifest(deviceId) }.getOrNull()
        var playlistName = "-"
        if (!rawManifest.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(rawManifest)
                val manifestObj = root.optJSONObject("manifest")
                playlistName = manifestObj?.optString("name", "-") ?: "-"
                val priceCfgObj = manifestObj?.optJSONObject("price_config") ?: root.optJSONObject("price_config")
                if (priceCfgObj != null) {
                    priceConfig = PriceConfigParser.parse(priceCfgObj.toString())
                }
            }
        }

        withContext(Dispatchers.Main) {
            binding.txtDeviceName.text = "Nome: ${cache?.deviceName ?: "-"}"
            binding.txtDeviceId.text = "Serial/ID: $deviceId"
            binding.txtPlaylistName.text = "Playlist Ativa: $playlistName"
            val companyDisplay = if (!cache?.companyName.isNullOrBlank()) {
                "${cache?.companyName} (${cache?.company})"
            } else {
                cache?.company?.ifBlank { null } ?: "-"
            }
            binding.txtCompanyTenant.text = "Empresa: $companyDisplay (Tenant: ${cache?.tenant?.ifBlank { null } ?: "-"})"
            binding.editFilial.setText(cache?.filial ?: "")

            // Get API endpoints being queried
            val stepsList = priceConfig?.steps
            if (!stepsList.isNullOrEmpty()) {
                val apiUrls = stepsList.joinToString("\n") { "${it.type}: [${it.method}] ${it.url}" }
                binding.txtApiEndpoint.text = "APIs de Consulta:\n$apiUrls"
            } else {
                binding.txtApiEndpoint.text = "API de Consulta: Supabase (Padrão)"
            }

            binding.editPriceHost.setText(settings?.priceHost ?: "")
            binding.editPricePort.setText(settings?.pricePort ?: "")
            binding.editImageHost.setText(
                runCatching { SettingsManager(applicationContext).getImageHost() }.getOrDefault(""),
            )
            binding.editImagePort.setText(
                runCatching { SettingsManager(applicationContext).getImagePort() }.getOrDefault(""),
            )

            binding.switchDevMode.isChecked = settings?.devMode ?: false
            binding.switchDemoMode.isChecked = settings?.demoMode ?: false
            binding.editTcServer.setText(
                runCatching { SettingsManager(applicationContext).getTcServerAddress() }.getOrDefault(""),
            )
            binding.editUnidasulServer.setText(
                runCatching { SettingsManager(applicationContext).getUnidasulServerAddress() }.getOrDefault(""),
            )
            binding.editCustomQueryXml.setText(
                runCatching { SettingsManager(applicationContext).getCustomQueryXml() }.getOrDefault(""),
            )
            binding.editHeartbeatIdleMinutes.setText(
                runCatching { SettingsManager(applicationContext).getHeartbeatIdleMinutes() }
                    .getOrDefault(SettingsManager.DEFAULT_HEARTBEAT_IDLE_MINUTES)
                    .toString(),
            )
            binding.switchForeignCurrency.isChecked =
                runCatching { SettingsManager(applicationContext).getForeignCurrencyEnabled() }.getOrDefault(false)
            binding.switchShowUsd.isChecked =
                runCatching { SettingsManager(applicationContext).getShowUsdCurrency() }.getOrDefault(true)
            binding.switchShowArs.isChecked =
                runCatching { SettingsManager(applicationContext).getShowArsCurrency() }.getOrDefault(true)
            binding.switchAdArtwork.isChecked =
                runCatching { SettingsManager(applicationContext).getAdArtworkEnabled() }.getOrDefault(true)
            binding.btnTestFaceRecognition.visibility = if (settings?.devMode == true) View.VISIBLE else View.GONE
            
            // Read Maintenance Mode setting
            val prefs = applicationContext.settingsDataStore.data.first()
            maintenanceModeEnabled = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("maintenance_mode")] ?: false
            binding.switchMaintenanceMode.isChecked = maintenanceModeEnabled
            binding.cardMaintenance.visibility = if (maintenanceModeEnabled) View.VISIBLE else View.GONE
        }
    }

    /** Retorna a mensagem de erro, ou null se a configuração for válida. Host em branco é
     *  válido e significa "esta loja não usa servidor local de preços". */
    private fun validatePriceServer(host: String, port: String): String? {
        if (host.isBlank() && port.isBlank()) return null
        if (host.isBlank()) return "Informe o IP do servidor (ou limpe a porta para desativar)."

        val octets = host.split(".")
        val validIp = octets.size == 4 && octets.all { o ->
            o.isNotBlank() && o.length <= 3 && o.all { it.isDigit() } && o.toInt() in 0..255
        }
        if (!validIp) return "IP inválido. Use o formato 192.168.0.10."

        val portNum = port.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) return "Porta inválida. Use um valor entre 1 e 65535."

        return null
    }

    /**
     * Bate no endpoint com um EAN sabidamente inexistente: o objetivo é provar alcance de
     * rede, não achar produto. Qualquer resposta HTTP (inclusive 404) significa servidor no ar.
     */
    private fun testPriceServer(host: String, port: String) {
        binding.btnTestPriceServer.isEnabled = false
        binding.txtPriceServerStatus.text = "Testando $host:$port..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(7, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("http://$host:$port/consulta?ean=0000000000000")
                    .get()
                    .build()
                runCatching {
                    client.newCall(request).execute().use { resp -> resp.code }
                }
            }

            result.fold(
                onSuccess = { code ->
                    binding.txtPriceServerStatus.text =
                        "OK - servidor respondeu (HTTP $code) em $host:$port"
                },
                onFailure = { t ->
                    binding.txtPriceServerStatus.text =
                        "FALHOU - ${t.javaClass.simpleName}: ${t.message ?: "sem detalhe"}"
                },
            )
            binding.btnTestPriceServer.isEnabled = true
        }
    }

    /** Bate em GET /produto-imagem/{ean} no servidor de imagens configurado (ou no srv-mupa de
     * produção, se em branco) e mostra o resultado bruto — usado pra confirmar se a arte/foto
     * já está disponível antes de testar no dispositivo de verdade. */
    private fun searchImageForEan(ean: String) {
        binding.btnSearchImage.isEnabled = false
        binding.txtImageSearchStatus.text = "Buscando imagem de $ean..."

        lifecycleScope.launch {
            val baseUrl = SettingsManager(applicationContext).getImageServerBaseUrl()
            val result = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/produto-imagem/$ean")
                    .get()
                    .build()
                runCatching {
                    client.newCall(request).execute().use { resp ->
                        Pair(resp.code, resp.body?.string().orEmpty())
                    }
                }
            }

            result.fold(
                onSuccess = { (code, body) ->
                    binding.txtImageSearchStatus.text = "$baseUrl (HTTP $code)\n$body"
                },
                onFailure = { t ->
                    binding.txtImageSearchStatus.text =
                        "FALHOU ($baseUrl) - ${t.javaClass.simpleName}: ${t.message ?: "sem detalhe"}"
                },
            )
            binding.btnSearchImage.isEnabled = true
        }
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnSaveFilial.setOnClickListener {
            val filialVal = binding.editFilial.text.toString().trim()
            if (filialVal.isBlank()) {
                Toast.makeText(this, "Por favor, digite uma filial válida.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val mgr = DeviceCacheManager(applicationContext)
                val current = mgr.load()
                if (current != null) {
                    val updated = DeviceCache(
                        deviceDbId = current.deviceDbId,
                        deviceId = current.deviceId,
                        deviceName = current.deviceName,
                        filial = filialVal,
                        company = current.company,
                        companyCode = current.companyCode,
                        companyName = current.companyName,
                        tenant = current.tenant,
                        lastSyncEpochMs = current.lastSyncEpochMs,
                        deviceRegistered = current.deviceRegistered,
                        tipoDaLicenca = current.tipoDaLicenca
                    )
                    mgr.save(updated)
                    Toast.makeText(this@SettingsActivity, "Filial atualizada para $filialVal", Toast.LENGTH_SHORT).show()
                    loadDeviceInformation()
                } else {
                    Toast.makeText(this@SettingsActivity, "Não foi possível carregar o cadastro atual.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSaveHeartbeatIdleMinutes.setOnClickListener {
            val minutes = binding.editHeartbeatIdleMinutes.text.toString().trim().toIntOrNull()
            if (minutes == null) {
                Toast.makeText(this, "Digite um número de minutos válido.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                SettingsManager(applicationContext).setHeartbeatIdleMinutes(minutes)
                val saved = SettingsManager(applicationContext).getHeartbeatIdleMinutes()
                binding.editHeartbeatIdleMinutes.setText(saved.toString())
                Toast.makeText(this@SettingsActivity, "Heartbeat: ociosidade de $saved min.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveTcServer.setOnClickListener {
            val address = binding.editTcServer.text.toString().trim()
            if (address.isNotBlank() && !address.matches(Regex("""^[\w.\-]+(:\d{1,5})?$"""))) {
                Toast.makeText(this, "Formato inválido. Use IP:Porta (ex: 192.168.0.10:8080)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                SettingsManager(applicationContext).setTcServerAddress(address)
                val msg = if (address.isBlank()) {
                    "TCServer desativado. Consulta volta a usar as APIs."
                } else {
                    "TCServer salvo: $address. Consulta de preço usará o TCServer."
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSaveCustomQueryXml.setOnClickListener {
            val xml = binding.editCustomQueryXml.text.toString().trim()
            if (xml.isBlank()) {
                lifecycleScope.launch {
                    SettingsManager(applicationContext).setCustomQueryXml("")
                    binding.txtCustomQueryXmlStatus.text = "Desativado. Consulta volta a usar a empresa/manifesto."
                }
                return@setOnClickListener
            }
            val parsed = runCatching { PriceConfigXmlImporter.parse(xml) }.getOrNull()
            if (parsed == null) {
                binding.txtCustomQueryXmlStatus.text = "XML inválido — confira a tag raiz <mupa-studio-query> e os steps."
                return@setOnClickListener
            }
            lifecycleScope.launch {
                SettingsManager(applicationContext).setCustomQueryXml(xml)
                binding.txtCustomQueryXmlStatus.text =
                    "Importado: integration=${parsed.integration}, ${parsed.steps.size} step(s). " +
                        "Reabra o app para aplicar."
                Toast.makeText(this@SettingsActivity, "XML da consulta importado e salvo.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnTestTcServer.setOnClickListener {
            val address = binding.editTcServer.text.toString().trim()
            if (address.isBlank()) {
                Toast.makeText(this, "Digite o endereço do TCServer primeiro.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val baseAddr = address.removePrefix("http://").removePrefix("https://").trimEnd('/')
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Testando conexão com $baseAddr..."
            lifecycleScope.launch(Dispatchers.IO) {
                // Achado real (2026-10-21): o TCServer não expõe /health nem JSON — é o
                // endpoint HTML nativo dele (webroot/index.jsp), o mesmo que a página de
                // consulta web do TCServer usa. "Consulta de Barcode" aparece tanto em
                // product.jsp (encontrado) quanto em notfound.jsp (não encontrado), então
                // serve como confirmação de que é mesmo um TCServer respondendo.
                val result = runCatching {
                    val url = java.net.URL("http://$baseAddr/barcode?param=0000000000000")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    code to text
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { (code, text) ->
                        if (code == 200 && text.contains("Consulta de Barcode")) {
                            binding.txtSyncProgress.text = "Conexão OK! TCServer Mupa online."
                            Toast.makeText(this@SettingsActivity, "TCServer Mupa conectado!", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.txtSyncProgress.text = "Conectou (HTTP $code), mas resposta inesperada."
                        }
                    }.onFailure { e ->
                        binding.txtSyncProgress.text = "Falha na conexão: ${e.message}"
                        Toast.makeText(this@SettingsActivity, "Não foi possível conectar.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnSaveUnidasulServer.setOnClickListener {
            val address = binding.editUnidasulServer.text.toString().trim()
            if (address.isNotBlank() && !address.matches(Regex("""^[\w.\-]+(:\d{1,5})?$"""))) {
                Toast.makeText(this, "Formato inválido. Use um host (ex: f002srv01.filial.unidasul.local)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                SettingsManager(applicationContext).setUnidasulServerAddress(address)
                val msg = if (address.isBlank()) {
                    "UnidaSul desativado. Consulta volta a usar as APIs."
                } else {
                    "UnidaSul salvo: $address. Consulta de preço usará o servidor da loja."
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        // API oficial UnidaSul.Api.TerminalConsulta (2026-11-11, substituiu o bridge local
        // /get_price — ver PriceQueryEngine.queryUnidasul) tem endpoint de status dedicado
        // (GET /ObterStatus, sem autenticação dentro da rede da loja) — usa ele pro teste
        // de conexão em vez de bater em ObterDadosPrecoProduto com um EAN fake.
        binding.btnTestUnidasulServer.setOnClickListener {
            val address = binding.editUnidasulServer.text.toString().trim()
            if (address.isBlank()) {
                Toast.makeText(this, "Digite o host do servidor da UnidaSul primeiro.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = address.removePrefix("http://").removePrefix("https://").trimEnd('/')
            // Só os 3 dígitos da filial importam — a URL de verdade é sempre
            // "f{filial}srvhml01.filial.unidasul.local/UnidaSul.Api.TerminalConsulta" (achado
            // real 2026-11-13, ver comentário de com.mupa.player.enterprise.price.unidasulBuildUrl),
            // reusada aqui em vez de duplicar a regra.
            val testUrl = unidasulBuildUrl(host, "ObterStatus")
            if (testUrl == null) {
                Toast.makeText(this, "Host inválido — não achei os 3 dígitos da filial (ex: f002...).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Testando conexão com $host..."
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val url = java.net.URL(testUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    code to text
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { (code, text) ->
                        if (code in 200..299 && text.contains("API Online", ignoreCase = true)) {
                            binding.txtSyncProgress.text = "Conexão OK! API UnidaSul online (HTTP $code)."
                            Toast.makeText(this@SettingsActivity, "Servidor UnidaSul conectado!", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.txtSyncProgress.text = "Conectou (HTTP $code), mas resposta inesperada."
                        }
                    }.onFailure { e ->
                        binding.txtSyncProgress.text = "Falha na conexão: ${e.message}"
                        Toast.makeText(this@SettingsActivity, "Não foi possível conectar.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.switchDevMode.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                SettingsManager(applicationContext).setDevMode(isChecked)
                binding.btnTestFaceRecognition.visibility = if (isChecked) View.VISIBLE else View.GONE
                Toast.makeText(this@SettingsActivity, if (isChecked) "Modo Dev Ativado" else "Modo Dev Desativado", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchForeignCurrency.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                SettingsManager(applicationContext).setForeignCurrencyEnabled(isChecked)
                Toast.makeText(
                    this@SettingsActivity,
                    if (isChecked) "Moeda estrangeira ativada" else "Moeda estrangeira desativada",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        binding.switchShowUsd.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { SettingsManager(applicationContext).setShowUsdCurrency(isChecked) }
        }

        binding.switchShowArs.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { SettingsManager(applicationContext).setShowArsCurrency(isChecked) }
        }

        binding.switchAdArtwork.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { SettingsManager(applicationContext).setAdArtworkEnabled(isChecked) }
        }

        binding.btnSavePriceServer.setOnClickListener {
            val host = binding.editPriceHost.text.toString().trim()
            val port = binding.editPricePort.text.toString().trim()
            val error = validatePriceServer(host, port)
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val mgr = SettingsManager(applicationContext)
                mgr.setPriceHost(host)
                mgr.setPricePort(port)
                val msg =
                    if (host.isBlank()) "Servidor local desativado."
                    else "Servidor local salvo: $host:$port"
                binding.txtPriceServerStatus.text = msg
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestPriceServer.setOnClickListener {
            val host = binding.editPriceHost.text.toString().trim()
            val port = binding.editPricePort.text.toString().trim()
            val error = validatePriceServer(host, port)
                ?: if (host.isBlank()) "Informe o IP do servidor para testar." else null
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testPriceServer(host, port)
        }

        binding.btnSaveImageServer.setOnClickListener {
            val host = binding.editImageHost.text.toString().trim()
            val port = binding.editImagePort.text.toString().trim()
            if (port.isNotBlank() && port.toIntOrNull() !in 1..65535) {
                Toast.makeText(this, "Porta inválida. Use um valor entre 1 e 65535.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val mgr = SettingsManager(applicationContext)
                mgr.setImageHost(host)
                mgr.setImagePort(port)
                val effective = mgr.getImageServerBaseUrl()
                val msg = "Servidor de imagens salvo: $effective"
                binding.txtImageSearchStatus.text = msg
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSearchImage.setOnClickListener {
            val ean = binding.editImageSearchEan.text.toString().trim()
            if (ean.isBlank()) {
                Toast.makeText(this, "Informe um EAN para buscar.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            searchImageForEan(ean)
        }

        binding.btnTestFaceRecognition.setOnClickListener {
            startActivity(Intent(this, FaceRecognitionTestActivity::class.java))
        }

        binding.switchDemoMode.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                SettingsManager(applicationContext).setDemoMode(isChecked)
                Toast.makeText(this@SettingsActivity, if (isChecked) "Modo Demo Ativado" else "Modo Demo Desativado", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchMaintenanceMode.setOnCheckedChangeListener { _, isChecked ->
            maintenanceModeEnabled = isChecked
            binding.cardMaintenance.visibility = if (isChecked) View.VISIBLE else View.GONE
            lifecycleScope.launch {
                applicationContext.settingsDataStore.edit {
                    it[androidx.datastore.preferences.core.booleanPreferencesKey("maintenance_mode")] = isChecked
                }
            }
        }

        binding.btnViewManifest.setOnClickListener {
            lifecycleScope.launch {
                val rawManifest = runCatching { ManifestManager(applicationContext).loadOfflineManifest(deviceId) }.getOrNull()
                if (rawManifest.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, "Nenhum manifesto local encontrado.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val options = arrayOf("Manifesto Completo", "Seção Playlist", "Seção Config de Preço")
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Escolha a seção para exibir")
                    .setItems(options) { _, which ->
                        val jsonToShow = try {
                            val root = JSONObject(rawManifest)
                            when (which) {
                                0 -> root.toString(2)
                                1 -> root.optJSONObject("manifest")?.optJSONObject("playlist")?.toString(2) ?: "Não encontrado"
                                2 -> root.optJSONObject("price_config")?.toString(2) ?: "Não encontrado"
                                else -> rawManifest
                            }
                        } catch (e: Exception) {
                            rawManifest
                        }
                        showManifestTextDialog(jsonToShow)
                    }
                    .show()
            }
        }

        binding.btnDownloadManifest.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Baixando manifesto..."
            lifecycleScope.launch {
                val mgr = ManifestManager(applicationContext)
                when (val result = mgr.fetchManifest(deviceId)) {
                    is ManifestFetchResult.Success -> {
                        if (result.json.isNotBlank()) {
                            mgr.saveManifest(deviceId, result.json)
                            binding.txtSyncProgress.text = "Manifesto baixado com sucesso!"
                            loadDeviceInformation()
                        } else {
                            binding.txtSyncProgress.text = "Falha ao baixar o manifesto: resposta vazia."
                        }
                    }
                    is ManifestFetchResult.HttpError -> binding.txtSyncProgress.text = "Falha ao baixar o manifesto: HTTP ${result.code} ${result.message}"
                    is ManifestFetchResult.NetworkError -> binding.txtSyncProgress.text = "Falha ao baixar o manifesto: ${result.message}"
                }
            }
        }

        binding.btnSyncTest.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Iniciando teste de sincronização..."
            lifecycleScope.launch {
                val mgr = ManifestManager(applicationContext)
                val fetchResult = mgr.fetchManifest(deviceId)
                if (fetchResult is ManifestFetchResult.Success && fetchResult.json.isNotBlank()) {
                    val raw = fetchResult.json
                    mgr.saveManifest(deviceId, raw)
                    binding.txtSyncProgress.text = "Manifesto baixado. Verificando mídias..."
                    runCatching {
                        mgr.syncMedia(
                            deviceId = deviceId,
                            manifestJson = raw,
                            onProgress = { p ->
                                lifecycleScope.launch(Dispatchers.Main) {
                                    binding.txtSyncProgress.text = "Sincronizando: ${p.completedItems} de ${p.totalItems} arquivos (${p.currentName ?: ""})"
                                }
                            }
                        )
                    }.onSuccess {
                        binding.txtSyncProgress.text = "Sincronização concluída com sucesso!"
                    }.onFailure { error ->
                        binding.txtSyncProgress.text = "Erro na sincronização: ${error.message}"
                    }
                } else {
                    binding.txtSyncProgress.text = "Falha no download inicial do manifesto: ${describeManifestFetchErrorForDisplay(fetchResult)}"
                }
            }
        }

        binding.btnUploadDetections.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Verificando dados de detecção..."
            lifecycleScope.launch {
                val db = AppDatabase.get(applicationContext)
                val count = db.audienceSessionDao().getPendingCount()
                if (count == 0) {
                    binding.txtSyncProgress.text = "Nenhum dado de detecção pendente para envio."
                    Toast.makeText(this@SettingsActivity, "Nenhum dado pendente.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                binding.txtSyncProgress.text = "Enviando $count detecção(ões) para o Supabase..."
                val success = AudienceSyncManager(applicationContext).uploadPending()
                if (success) {
                    binding.txtSyncProgress.text = "Detecções enviadas com sucesso!"
                    Toast.makeText(this@SettingsActivity, "Detecções enviadas com sucesso!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.txtSyncProgress.text = "Falha ao enviar detecções para o Supabase."
                    Toast.makeText(this@SettingsActivity, "Falha no envio.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnUploadPriceQueries.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Verificando consultas de preços..."
            lifecycleScope.launch {
                val db = AppDatabase.get(applicationContext)
                val count = db.priceQueryEventDao().getPendingCount()
                if (count == 0) {
                    binding.txtSyncProgress.text = "Nenhuma consulta de preço pendente para envio."
                    Toast.makeText(this@SettingsActivity, "Nenhuma consulta pendente.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                binding.txtSyncProgress.text = "Enviando $count consulta(s) de preços para o Supabase..."
                val success = PriceAnalyticsSyncManager(applicationContext).uploadPending()
                if (success) {
                    binding.txtSyncProgress.text = "Consultas de preços enviadas com sucesso!"
                    Toast.makeText(this@SettingsActivity, "Consultas enviadas com sucesso!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.txtSyncProgress.text = "Falha ao enviar consultas de preços para o Supabase."
                    Toast.makeText(this@SettingsActivity, "Falha no envio.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnUploadMissingImages.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Verificando produtos sem imagem..."
            lifecycleScope.launch {
                val db = AppDatabase.get(applicationContext)
                val count = db.missingProductImageDao().getPendingCount()
                if (count == 0) {
                    binding.txtSyncProgress.text = "Nenhum produto sem imagem pendente para envio."
                    Toast.makeText(this@SettingsActivity, "Nenhum pendente.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                binding.txtSyncProgress.text = "Enviando $count produto(s) sem imagem para o Supabase..."
                val success = MissingProductImageSyncManager(applicationContext).uploadPending()
                if (success) {
                    binding.txtSyncProgress.text = "Produtos sem imagem enviados com sucesso!"
                    Toast.makeText(this@SettingsActivity, "Enviado com sucesso!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.txtSyncProgress.text = "Falha ao enviar produtos sem imagem para o Supabase."
                    Toast.makeText(this@SettingsActivity, "Falha no envio.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSimulateQuery.setOnClickListener {
            val barcode = binding.editSimulateBarcode.text.toString().trim()
            if (barcode.isBlank()) {
                Toast.makeText(this, "Digite um código de barras para simular.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Simulando consulta para: $barcode..."
            lifecycleScope.launch {
                val engine = PriceQueryEngine(applicationContext, deviceId)
                val cfg = priceConfig ?: run {
                    loadDeviceInformation()
                    priceConfig
                }

                if (cfg == null) {
                    withContext(Dispatchers.Main) {
                        binding.txtSyncProgress.text = "Erro: Configuração de preço não disponível."
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Simulador")
                            .setMessage("Nenhuma configuração de preço ativa no manifest.json local.")
                            .setPositiveButton("Ok", null)
                            .show()
                    }
                    return@launch
                }

                val product = runCatching { engine.query(barcode, cfg, true) }.getOrNull()
                withContext(Dispatchers.Main) {
                    binding.txtSyncProgress.text = "Consulta simulada concluída!"
                    if (product != null) {
                        val detailText = StringBuilder().apply {
                            append("EAN: ${product.ean}\n")
                            append("Descrição: ${product.description}\n")
                            append("Preço: R$ ${product.price}\n")
                            append("Preço Original: R$ ${product.originalPrice}\n")
                            append("Preço Clube: R$ ${product.clubPrice}\n")
                            append("Offline: ${product.offline}\n")
                            append("Packs: ${product.packs.size} combo(s)\n")
                            product.packs.forEach {
                                append("  - ${it.label}: R$ ${it.price} (unit: R$ ${it.unitPrice})\n")
                            }
                        }.toString()
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Produto Retornado")
                            .setMessage(detailText)
                            .setPositiveButton("Ok", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Produto Retornado")
                            .setMessage("Nenhum produto correspondente retornado da API local.")
                            .setPositiveButton("Ok", null)
                            .show()
                    }
                }
            }
        }

        binding.btnCloseApp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Fechar o aplicativo?")
                .setMessage("Tem certeza que deseja encerrar o MPlayer?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Sim, Fechar") { _, _ ->
                    finishAffinity()
                    System.exit(0)
                }
                .show()
        }

        binding.btnResetApp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Apagar dados do app?")
                .setMessage("Isso apagará cadastro, mídias e configurações. O app reiniciará na tela inicial.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar") { _, _ ->
                    lifecycleScope.launch {
                        Toast.makeText(this@SettingsActivity, "Apagando dados...", Toast.LENGTH_SHORT).show()
                        withContext(Dispatchers.IO) { wipeAppDataInternal() }
                        runCatching {
                            startActivity(
                                Intent(this@SettingsActivity, SplashActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            )
                        }
                        finishAffinity()
                        Process.killProcess(Process.myPid())
                    }
                }
                .show()
        }
    }

    private fun describeManifestFetchErrorForDisplay(result: ManifestFetchResult): String = when (result) {
        is ManifestFetchResult.HttpError -> "HTTP ${result.code} ${result.message}"
        is ManifestFetchResult.NetworkError -> result.message
        is ManifestFetchResult.Success -> "resposta vazia"
    }

    private fun showManifestTextDialog(text: String) {
        val container = android.widget.FrameLayout(this)
        val scroll = android.widget.ScrollView(this)
        val txt = com.google.android.material.textview.MaterialTextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(resources.getColor(android.R.color.white))
        }
        scroll.addView(txt)
        container.addView(scroll)

        AlertDialog.Builder(this)
            .setTitle("Visualizar Manifesto")
            .setView(container)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private suspend fun wipeAppDataInternal() {
        runCatching { AppDatabase.get(applicationContext).close() }
        runCatching { applicationContext.deleteDatabase("mplayer.db") }
        runCatching { deleteRecursivelySafely(File(applicationInfo.dataDir, "databases")) }

        runCatching { applicationContext.settingsDataStore.edit { it.clear() } }
        runCatching { applicationContext.getSharedPreferences("mupa_settings_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { applicationContext.getSharedPreferences("mupa_device_cache_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { applicationContext.getSharedPreferences("mupa_device_identity_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { deleteRecursivelySafely(File(applicationInfo.dataDir, "shared_prefs")) }

        filesDir.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
        cacheDir.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
        getExternalFilesDir(null)?.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
    }

    private fun deleteRecursivelySafely(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursivelySafely(it) }
        }
        file.delete()
    }
}
