package com.mupa.player.enterprise.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.databinding.ActivitySettingsBinding
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.price.PriceConfig
import com.mupa.player.enterprise.price.PriceConfigParser
import com.mupa.player.enterprise.price.PriceQueryEngine
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.settingsDataStore
import com.mupa.player.enterprise.audience.AudienceSyncManager
import com.mupa.player.enterprise.price.PriceAnalyticsSyncManager
import com.mupa.player.enterprise.player.MediaPlayLogsSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class SettingsActivity : ComponentActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var deviceId: String
    private var priceConfig: PriceConfig? = null
    private var maintenanceModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
            loadDeviceInformation()
            setupListeners()
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

            binding.switchDevMode.isChecked = settings?.devMode ?: false
            binding.switchDemoMode.isChecked = settings?.demoMode ?: false
            binding.btnTestFaceRecognition.visibility = if (settings?.devMode == true) View.VISIBLE else View.GONE
            
            // Read Maintenance Mode setting
            val prefs = applicationContext.settingsDataStore.data.first()
            maintenanceModeEnabled = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("maintenance_mode")] ?: false
            binding.switchMaintenanceMode.isChecked = maintenanceModeEnabled
            binding.cardMaintenance.visibility = if (maintenanceModeEnabled) View.VISIBLE else View.GONE
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

        binding.switchDevMode.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                SettingsManager(applicationContext).setDevMode(isChecked)
                binding.btnTestFaceRecognition.visibility = if (isChecked) View.VISIBLE else View.GONE
                Toast.makeText(this@SettingsActivity, if (isChecked) "Modo Dev Ativado" else "Modo Dev Desativado", Toast.LENGTH_SHORT).show()
            }
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
                val success = runCatching {
                    val mgr = ManifestManager(applicationContext)
                    val raw = mgr.fetchManifest(deviceId)
                    if (raw.isNotBlank()) {
                        mgr.saveManifest(deviceId, raw)
                        true
                    } else false
                }.getOrDefault(false)
                
                if (success) {
                    binding.txtSyncProgress.text = "Manifesto baixado com sucesso!"
                    loadDeviceInformation()
                } else {
                    binding.txtSyncProgress.text = "Falha ao baixar o manifesto."
                }
            }
        }

        binding.btnSyncTest.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Iniciando teste de sincronização..."
            lifecycleScope.launch {
                val mgr = ManifestManager(applicationContext)
                val raw = runCatching { mgr.fetchManifest(deviceId) }.getOrNull()
                if (!raw.isNullOrBlank()) {
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
                    binding.txtSyncProgress.text = "Falha no download inicial do manifesto."
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

        binding.btnUploadMediaPlayLogs.setOnClickListener {
            binding.txtSyncProgress.visibility = View.VISIBLE
            binding.txtSyncProgress.text = "Verificando relatório de mídias..."
            lifecycleScope.launch {
                val db = AppDatabase.get(applicationContext)
                val count = db.mediaPlayLogDao().getPendingCount()
                if (count == 0) {
                    binding.txtSyncProgress.text = "Nenhum log de reprodução de mídia pendente para envio."
                    Toast.makeText(this@SettingsActivity, "Nenhum log pendente.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                binding.txtSyncProgress.text = "Enviando $count log(s) de reprodução de mídias para o Supabase..."
                val success = MediaPlayLogsSyncManager(applicationContext).uploadPending()
                if (success) {
                    binding.txtSyncProgress.text = "Logs de mídias enviados com sucesso!"
                    Toast.makeText(this@SettingsActivity, "Relatório de mídias enviado com sucesso!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.txtSyncProgress.text = "Falha ao enviar relatório de mídias para o Supabase."
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
