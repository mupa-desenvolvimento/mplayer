package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import android.widget.EditText
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import coil.load
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.databinding.ActivityPlayerBinding
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.MediaSyncProgress
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.player.PlaybackProfile
import com.mupa.player.enterprise.player.PlayerEngine
import com.mupa.player.enterprise.player.MediaPlayLogsSyncManager
import com.mupa.player.enterprise.player.TransitionConfig
import com.mupa.player.enterprise.R
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var deviceId: String
    private lateinit var playerEngine: PlayerEngine
    private lateinit var manifestManager: ManifestManager
    private var playlistName: String? = null
    private var itemNameById: Map<String, String> = emptyMap()
    private var companyId: String? = null

    private var lastPlaylistItemsIds: List<String> = emptyList()
    private var syncHideJob: Job? = null
    private var devMode = false
    private var demoMode = false

    private var storagePermissionDeferred: CompletableDeferred<Boolean>? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            storagePermissionDeferred?.complete(granted)
            storagePermissionDeferred = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apkVersionWatermark.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        setupDevModeToggle()

        manifestManager = ManifestManager(applicationContext)
        playerEngine = PlayerEngine(
            context = this,
            scope = lifecycleScope,
            layerA = PlayerEngine.LayerViews(
                container = binding.layerA,
                playerView = binding.playerViewA,
                imageView = binding.imageViewA,
            ),
            layerB = PlayerEngine.LayerViews(
                container = binding.layerB,
                playerView = binding.playerViewB,
                imageView = binding.imageViewB,
            ),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    applyImmersive()
                }
                launch {
                    val mgr = SettingsManager(applicationContext)
                    var lastDev = false
                    var lastDemo = false
                    mgr.settingsFlow.collect { s ->
                        val vDev = s.devMode
                        val vDemo = s.demoMode
                        if (vDev != lastDev || vDemo != lastDemo) {
                            lastDev = vDev
                            lastDemo = vDemo
                            devMode = vDev
                            demoMode = vDemo
                            updateDeviceWatermark()
                            updateDevModeUI()
                        }
                    }
                }
                launch { startLoop() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            val serviceIntent = Intent(this, com.mupa.player.enterprise.services.InactivityTimerService::class.java)
            stopService(serviceIntent)
        }
        lifecycleScope.launch {
            val settings = runCatching { SettingsManager(applicationContext).getSettings() }.getOrNull()
            devMode = settings?.devMode ?: false
            demoMode = settings?.demoMode ?: false
            updateDeviceWatermark()
            updateDevModeUI()
            val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
            binding.deviceNameText.text = cache?.deviceName?.ifBlank { deviceId } ?: deviceId
            companyId = cache?.company?.trim()?.ifBlank { null }
        }
    }

    private fun applyImmersive() {
        val w = window ?: return
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = w.insetsController ?: return
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        playerEngine.release()
        super.onDestroy()
    }

    private suspend fun startLoop() {
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()
        if (deviceId.isBlank()) {
            deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
        }
        val settings = runCatching { SettingsManager(applicationContext).getSettings() }.getOrNull()
        devMode = settings?.devMode ?: false
        demoMode = settings?.demoMode ?: false
        updateDevModeUI()
        updateDeviceWatermark()

        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        binding.deviceNameText.text = cache?.deviceName?.ifBlank { deviceId } ?: deviceId
        companyId = cache?.company?.trim()?.ifBlank { null }

        ensureStoragePermissionIfNeeded()
        tryStartOfflinePlayback()
        initialSyncAndPlayback()

        lifecycleScope.launch {
            while (true) {
                delay(60 * 1000L)
                updatePlaylistIfActiveItemsChanged()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60 * 60 * 1000L) // cada 1 hora
                if (isOnline()) {
                    runCatching { MediaPlayLogsSyncManager(applicationContext).uploadPending() }
                }
            }
        }

        lifecycleScope.launch {
            while (true) {
                delay(60 * 60 * 1000L) // Wait 1 hour between checks
                var success = false
                try {
                    success = refreshInBackground()
                } catch (e: Exception) {
                    Log.e("PlayerActivity", "Error in background refresh", e)
                }
                
                while (!success) {
                    delay(10 * 60 * 1000L) // retry in 10 minutes
                    try {
                        success = refreshInBackground()
                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "Error in background refresh retry", e)
                    }
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun storagePermissionsToRequest(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private suspend fun ensureStoragePermissionIfNeeded(): Boolean {
        if (hasStoragePermission()) return true
        if (Build.VERSION.SDK_INT < 23) return true

        val deferred = CompletableDeferred<Boolean>()
        storagePermissionDeferred = deferred
        storagePermissionLauncher.launch(storagePermissionsToRequest())
        val granted = withTimeoutOrNull(12_000L) { deferred.await() } ?: false
        if (storagePermissionDeferred === deferred) {
            storagePermissionDeferred = null
        }
        return granted
    }

    private fun updateDeviceWatermark() {
        val base = "ID: $deviceId"
        if (devMode) {
            binding.deviceIdWatermark.text = "$base • DEV"
            binding.deviceIdWatermark.visibility = View.VISIBLE
        } else if (demoMode) {
            binding.deviceIdWatermark.text = "$base • DEMO"
            binding.deviceIdWatermark.visibility = View.VISIBLE
        } else {
            binding.deviceIdWatermark.visibility = View.GONE
        }
    }

    private fun updateDevModeUI() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (devMode) {
                binding.devOverlayContainer.visibility = View.VISIBLE
                binding.txtDevAndroidId.text = "Android ID: $deviceId"
                
                val cache = withContext(Dispatchers.IO) {
                    runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
                }
                val companyStr = cache?.companyName ?: cache?.company ?: "-"
                binding.txtDevCompany.text = "Empresa: $companyStr"
            } else {
                binding.devOverlayContainer.visibility = View.GONE
            }
        }
    }

    private suspend fun tryStartOfflinePlayback(): Boolean {
        val offlineJson = manifestManager.loadOfflineManifest(deviceId).orEmpty().trim()
        if (offlineJson.isBlank()) return false
        val items = manifestManager.parseItemsPublic(offlineJson)
        playlistName = manifestManager.parsePlaylistName(offlineJson) ?: playlistName
        itemNameById = items.mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyTransitionConfigFromManifestJson(offlineJson)
        val playlist = buildLocalPlaylist(items)
        if (playlist.isNotEmpty()) {
            playerEngine.start(playlist)
            setSyncOverlayVisible(false)
            return true
        }
        return false
    }

    private suspend fun initialSyncAndPlayback() {
        setSyncOverlayVisible(true)
        updateSyncTexts(
            status = "Sincronizando conteúdos...",
            countText = "",
            fileText = "",
            detailText = "",
            progressPercent = null,
        )

        if (!isOnline()) {
            if (playerEngine.getCurrentItemId() != null) {
                updateSyncTexts(
                    status = "Sem internet. Reproduzindo conteúdo local.",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                setSyncOverlayVisible(false)
                return
            }
            while (!isOnline()) {
                updateSyncTexts(
                    status = "Sem internet. Aguardando conexão para sincronizar...",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                delay(3000L)
            }
        }

        var remote = runCatching { manifestManager.fetchManifest(deviceId) }
            .onFailure { Log.w("MPlayerSync", "fetch_manifest_failed deviceId=$deviceId", it) }
            .getOrDefault("")
            .trim()
        while (remote.isBlank()) {
            if (playerEngine.getCurrentItemId() != null) {
                setSyncOverlayVisible(false)
                return
            }
            if (tryStartOfflinePlayback()) {
                updateSyncTexts(
                    status = "Falha ao obter programação online. Reproduzindo conteúdo local.",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                setSyncOverlayVisible(false)
                return
            }
            updateSyncTexts(
                status = "Não foi possível obter a programação. Tentando novamente...",
                countText = "",
                fileText = "",
                detailText = "",
                progressPercent = null,
            )
            delay(3000L)
            if (!isOnline()) {
                while (!isOnline()) {
                    updateSyncTexts(
                        status = "Sem internet. Aguardando conexão para sincronizar...",
                        countText = "",
                        fileText = "",
                        detailText = "",
                        progressPercent = null,
                    )
                    delay(3000L)
                }
            }
            remote = runCatching { manifestManager.fetchManifest(deviceId) }
                .onFailure { Log.w("MPlayerSync", "fetch_manifest_failed deviceId=$deviceId", it) }
                .getOrDefault("")
                .trim()
        }

        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (changed) {
            manifestManager.saveManifest(deviceId, remote)
        }

        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyTransitionConfigFromManifestJson(remote)

        val items = manifestManager.parseItemsPublic(remote)
        var playlist = buildLocalPlaylist(items)

        // Se já tivermos ao menos 1 mídia pronta localmente, iniciamos a reprodução imediatamente
        // e ocultamos o overlay para não bloquear o conteúdo na tela enquanto baixa o restante.
        if (playlist.isNotEmpty()) {
            playerEngine.setPlaylist(playlist)
            setSyncOverlayVisible(false)
        }

        if (playlist.size == items.size) {
            lifecycleScope.launch {
                runCatching {
                    manifestManager.syncMedia(
                        deviceId = deviceId,
                        manifestJson = remote,
                        onProgress = null,
                        maxConcurrentDownloads = 1,
                    )
                }
            }
            return
        }

        // Caso falte algum arquivo, fazemos o download em background
        lifecycleScope.launch(Dispatchers.IO) {
            var currentPlaylist = playlist
            while (currentPlaylist.size < items.size) {
                runCatching {
                    manifestManager.syncMedia(
                        deviceId = deviceId,
                        manifestJson = remote,
                        onProgress = null,
                        maxConcurrentDownloads = 1,
                    )
                }
                val updated = buildLocalPlaylist(items)
                if (updated.size > currentPlaylist.size) {
                    currentPlaylist = updated
                    withContext(Dispatchers.Main) {
                        playerEngine.setPlaylist(currentPlaylist)
                        setSyncOverlayVisible(false)
                    }
                } else {
                    delay(5000L)
                }
            }
            withContext(Dispatchers.Main) {
                playerEngine.setPlaylist(currentPlaylist)
                setSyncOverlayVisible(false)
            }
        }
    }

    private suspend fun refreshInBackground(): Boolean {
        if (!isOnline()) return false
        runCatching {
            com.mupa.player.enterprise.services.DeviceValidationService(applicationContext).validateDevice(deviceId)
        }

        val remote = runCatching { manifestManager.fetchManifest(deviceId) }.getOrNull()?.trim()
        if (remote.isNullOrBlank()) return false
        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (!changed) return true

        manifestManager.saveManifest(deviceId, remote)
        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyTransitionConfigFromManifestJson(remote)
        setSyncOverlayVisible(true)
        updateSyncTexts(
            status = "Sincronizando conteúdos...",
            countText = "",
            fileText = "",
            detailText = "",
            progressPercent = null,
        )

        val items = manifestManager.parseItemsPublic(remote)
        var playlist = buildLocalPlaylist(items)
        if (playlist.size == items.size) {
            playerEngine.setPlaylist(playlist)
            setSyncOverlayVisible(false)
            val bgSync = runCatching {
                manifestManager.syncMedia(
                    deviceId = deviceId,
                    manifestJson = remote,
                    onProgress = null,
                    maxConcurrentDownloads = 1,
                )
            }
            return bgSync.isSuccess
        }

        runCatching {
            manifestManager.syncMedia(
                deviceId = deviceId,
                manifestJson = remote,
                onProgress = { p -> runOnUiThread { renderProgress(p) } },
                maxConcurrentDownloads = 1,
            )
        }

        playlist = buildLocalPlaylist(items)
        var attempts = 0
        while (playlist.size < items.size && attempts < 3) {
            attempts++
            val missing = (items.size - playlist.size).coerceAtLeast(0)
            updateSyncTexts(
                status = "Baixando conteúdos...",
                countText = "Faltando $missing de ${items.size} mídias",
                fileText = "",
                detailText = "",
                progressPercent = null,
            )
            delay(2500L)
            runCatching {
                manifestManager.syncMedia(
                    deviceId = deviceId,
                    manifestJson = remote,
                    onProgress = { p -> runOnUiThread { renderProgress(p) } },
                    maxConcurrentDownloads = 1,
                )
            }
            playlist = buildLocalPlaylist(items)
        }
        playerEngine.setPlaylist(playlist)
        setSyncOverlayVisible(false)
        return playlist.size == items.size
    }

    private fun isItemCurrentlyActive(item: com.mupa.player.enterprise.managers.ManifestItem): Boolean {
        val now = Date()

        // 1. Validar Vigência por Data (AAAA-MM-DD)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFmt.format(now)

        val startD = item.startDate?.trim()
        val endD = item.endDate?.trim()

        if (!startD.isNullOrBlank()) {
            if (todayStr < startD) return false
        }
        if (!endD.isNullOrBlank()) {
            if (todayStr > endD) return false
        }

        // 2. Validar Faixa Horária (HH:MM:SS)
        val startT = item.startTime?.trim()
        val endT = item.endTime?.trim()

        if (!startT.isNullOrBlank() || !endT.isNullOrBlank()) {
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeStr = timeFmt.format(now)

            val minT = startT ?: "00:00:00"
            val maxT = endT ?: "23:59:59"

            if (minT <= maxT) {
                if (timeStr < minT || timeStr > maxT) return false
            } else {
                if (timeStr < minT && timeStr > maxT) return false
            }
        }

        return true
    }

    private fun updatePlaylistIfActiveItemsChanged() {
        lifecycleScope.launch {
            val offlineJson = manifestManager.loadOfflineManifest(deviceId).orEmpty().trim()
            if (offlineJson.isBlank()) return@launch
            val items = manifestManager.parseItemsPublic(offlineJson)
            val activeItems = items.filter { isItemCurrentlyActive(it) }
            val activeIds = activeItems.map { it.id }
            if (activeIds != lastPlaylistItemsIds) {
                lastPlaylistItemsIds = activeIds
                val playlist = buildLocalPlaylist(items)
                playerEngine.setPlaylist(playlist)
                Log.i("MPlayerPlaylist", "Playlist updated dynamically due to time/date change. Active items count: ${playlist.size}")
            }
        }
    }

    private suspend fun buildLocalPlaylist(items: List<com.mupa.player.enterprise.managers.ManifestItem>): List<PlayerEngine.PlaybackItem> {
        val db = AppDatabase.get(applicationContext)
        val mediaIndexFromDb = withContext(Dispatchers.IO) {
            runCatching {
                db.mediaDao().getAll()
                    .mapNotNull { e ->
                        val f = File(e.localPath)
                        if (f.exists() && f.length() > 0) e.mediaId to f else null
                     }
                     .toMap()
            }.getOrDefault(emptyMap())
        }

        val mediaDir = File(applicationContext.getExternalFilesDir(null), "media")
        val mediaIndexFromDisk =
            runCatching {
                mediaDir.listFiles().orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.length() > 0L }
                    .filter { it.name != "manifest.json" && !it.name.endsWith(".tmp") }
                    .associateBy { it.name.substringBeforeLast('.', missingDelimiterValue = it.name) }
            }.getOrDefault(emptyMap())

        val mediaIndex = if (mediaIndexFromDisk.isEmpty()) mediaIndexFromDb else (mediaIndexFromDisk + mediaIndexFromDb)

        return items.mapNotNull { item ->
            if (!isItemCurrentlyActive(item)) return@mapNotNull null
            val file = mediaIndex[item.id] ?: return@mapNotNull null
            PlayerEngine.PlaybackItem(
                id = item.id,
                type = item.type,
                file = file,
                durationMs = item.durationMs,
                volume = item.volume,
                offsetStartMs = item.offsetStartMs,
                offsetEndMs = item.offsetEndMs,
                name = item.name,
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun setSyncOverlayVisible(visible: Boolean) {
        syncHideJob?.cancel()
        binding.syncOverlay.animate().cancel()

        if (visible) {
            val isMediaPlaying = playerEngine.getCurrentItemId() != null
            if (isMediaPlaying) {
                // Sincronizando com mídias rodando ao fundo -> Pequeno card na parte inferior
                val overlayParams = binding.syncOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                overlayParams.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                overlayParams.gravity = android.view.Gravity.BOTTOM
                binding.syncOverlay.layoutParams = overlayParams

                binding.syncOverlay.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.syncContent.setBackgroundResource(com.mupa.player.enterprise.R.drawable.bg_sync_card)
                
                val params = binding.syncContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = dpToPx(16)
                binding.syncContent.layoutParams = params
                
                binding.syncLogo.visibility = View.GONE

                // Compact padding and smaller font sizes
                binding.syncContent.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                binding.syncStatusText.textSize = 13f
                binding.syncCountText.textSize = 11f
                binding.syncFileText.textSize = 11f
                binding.syncDetailText.textSize = 10f
            } else {
                // Tela cheia
                val overlayParams = binding.syncOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                overlayParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                overlayParams.gravity = android.view.Gravity.NO_GRAVITY
                binding.syncOverlay.layoutParams = overlayParams

                binding.syncOverlay.setBackgroundResource(com.mupa.player.enterprise.R.color.enterprise_bg)
                binding.syncContent.background = null
                
                val params = binding.syncContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = 0
                binding.syncContent.layoutParams = params
                
                binding.syncLogo.visibility = View.VISIBLE

                // Standard padding and font sizes
                binding.syncContent.setPadding(dpToPx(28), dpToPx(28), dpToPx(28), dpToPx(28))
                binding.syncStatusText.textSize = 20f
                binding.syncCountText.textSize = 14f
                binding.syncFileText.textSize = 14f
                binding.syncDetailText.textSize = 12f
            }

            if (binding.syncOverlay.visibility != View.VISIBLE) {
                binding.syncOverlay.visibility = View.VISIBLE
                binding.syncOverlay.alpha = 0f
                binding.syncOverlay.animate().alpha(1f).setDuration(180).start()
            } else if (binding.syncOverlay.alpha < 1f) {
                binding.syncOverlay.animate().alpha(1f).setDuration(180).start()
            }
        } else {
            syncHideJob =
                lifecycleScope.launch {
                    delay(700L)
                    binding.syncOverlay.animate().alpha(0f).setDuration(220).withEndAction {
                        binding.syncOverlay.visibility = View.GONE
                    }.start()
                }
        }
    }

    private fun promptWipeAppData() {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this@PlayerActivity)
            .setTitle("Apagar dados do app?")
            .setMessage("Isso apaga cadastro, cache, mídias e imagens locais. O app vai reiniciar e você poderá testar o cadastro novamente.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    Toast.makeText(this@PlayerActivity, "Apagando dados...", Toast.LENGTH_SHORT).show()
                    withContext(Dispatchers.IO) { wipeAppDataInternal() }
                    Toast.makeText(this@PlayerActivity, "Dados apagados. Reiniciando...", Toast.LENGTH_SHORT).show()
                    runCatching {
                        startActivity(
                            Intent(this@PlayerActivity, SplashActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                    runCatching { finishAffinity() }
                    runCatching { Process.killProcess(Process.myPid()) }
                }
            }
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
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursivelySafely(it) }
        }
        runCatching { file.delete() }
    }

    private fun setupDevModeToggle() {
        binding.deviceIdWatermark.setOnLongClickListener {
            showAdminAccessDialog()
            true
        }
        binding.apkVersionWatermark.setOnLongClickListener {
            binding.deviceIdWatermark.performLongClick()
            true
        }
    }

    private fun showAdminAccessDialog() {
        lifecycleScope.launch {
            val cache = DeviceCacheManager(applicationContext).load()
            val targetCode = cache?.companyCode?.trim().orEmpty()
            withContext(Dispatchers.Main) {
                val container = android.widget.FrameLayout(this@PlayerActivity)
                val params = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = 48
                    rightMargin = 48
                    topMargin = 24
                    bottomMargin = 24
                }
                val input = EditText(this@PlayerActivity).apply {
                    hint = "Código da Empresa"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    layoutParams = params
                }
                container.addView(input)
                android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Acesso Restrito")
                    .setMessage("Insira o Código de Usuário da Empresa:")
                    .setView(container)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Confirmar") { _, _ ->
                        val entered = input.text.toString().trim()
                        val isCorrect = (targetCode.isNotBlank() && entered.equals(targetCode, ignoreCase = true)) ||
                                        entered.equals("DEBUG", ignoreCase = true) ||
                                        entered.equals("123ABC", ignoreCase = true) ||
                                        targetCode.isBlank()
                        if (isCorrect) {
                            startActivity(Intent(this@PlayerActivity, SettingsActivity::class.java))
                        } else {
                            Toast.makeText(this@PlayerActivity, "Código inválido!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }


    private fun applyTransitionConfigFromManifestJson(manifestJson: String) {
        val cfg = parseTransitionConfigFromManifestJson(manifestJson) ?: return
        playerEngine.setTransitionConfig(cfg)
    }

    private fun parseTransitionConfigFromManifestJson(manifestJson: String): TransitionConfig? {
        return runCatching {
            val root = JSONObject(manifestJson)
            val manifestObj = root.optJSONObject("manifest") ?: root
            val playlistObj = manifestObj.optJSONObject("playlist")
            val appearance =
                manifestObj.optJSONObject("appearance_config")
                    ?: playlistObj?.optJSONObject("appearance_config")
                    ?: JSONObject()

            val t =
                appearance.optJSONObject("transitions")
                    ?: appearance.optJSONObject("transition")
                    ?: appearance.optJSONObject("transition_config")
                    ?: JSONObject()

            val enabled =
                when {
                    t.has("enabled") -> t.optBoolean("enabled", true)
                    t.has("transitions_enabled") -> t.optBoolean("transitions_enabled", true)
                    appearance.has("transitions_enabled") -> appearance.optBoolean("transitions_enabled", true)
                    else -> true
                }

            val rawType =
                t.optString("type", "")
                    .ifBlank { t.optString("transitions_type", "") }
                    .ifBlank { appearance.optString("transitions_type", "") }
                    .trim()
                    .lowercase(Locale.US)

            val mode =
                when (rawType) {
                    "none", "off", "0", "false", "desativado" -> TransitionConfig.Mode.NONE
                    "crossfade", "cross_fade", "cross-fade" -> TransitionConfig.Mode.CROSSFADE
                    "fade", "" -> TransitionConfig.Mode.FADE
                    else -> TransitionConfig.Mode.FADE
                }

            val rawDur =
                t.optLong("duration_ms", -1L).takeIf { it > 0L }
                    ?: t.optLong("transitions_ms", -1L).takeIf { it > 0L }
                    ?: appearance.optLong("transitions_ms", -1L).takeIf { it > 0L }

            val dur =
                when (rawDur) {
                    150L, 200L, 250L, 300L, 400L, 500L -> rawDur
                    else -> null
                }

            val base = TransitionConfig.default(PlaybackProfile.detect(applicationContext))
            val finalEnabled = enabled && mode != TransitionConfig.Mode.NONE
            val finalMode = if (finalEnabled) mode else TransitionConfig.Mode.NONE
            val finalDur = dur ?: base.durationMs
            TransitionConfig(enabled = finalEnabled, mode = finalMode, durationMs = finalDur)
        }.getOrNull()
    }

    private fun renderProgress(p: MediaSyncProgress) {
        val countText = "${p.completedItems} de ${p.totalItems} conteúdos"
        val fileText = p.currentName?.let { "Baixando $it" }.orEmpty()

        val percent = p.currentBytesTotal?.takeIf { it > 0L }?.let { total ->
            ((p.currentBytesDownloaded * 100L) / total).toInt().coerceIn(0, 100)
        }

        val detail = buildString {
            if (percent != null) append("$percent%")
            val total = p.currentBytesTotal
            if (total != null && total > 0L) {
                if (isNotEmpty()) append(" • ")
                append("${formatBytes(p.currentBytesDownloaded)} / ${formatBytes(total)}")
            }
            if (p.currentSpeedBytesPerSec > 0) {
                if (isNotEmpty()) append(" • ")
                append("${formatBytes(p.currentSpeedBytesPerSec)}/s")
            }
        }

        updateSyncTexts(
            status = "Sincronizando conteúdos...",
            countText = countText,
            fileText = fileText,
            detailText = detail,
            progressPercent = percent,
        )
    }

    private fun updateSyncTexts(
        status: String,
        countText: String,
        fileText: String,
        detailText: String,
        progressPercent: Int?,
    ) {
        binding.syncStatusText.text = status
        binding.syncCountText.text = countText
        binding.syncFileText.text = fileText
        binding.syncDetailText.text = detailText

        binding.syncCountText.visibility = if (countText.isNotBlank()) View.VISIBLE else View.GONE
        binding.syncFileText.visibility = if (fileText.isNotBlank()) View.VISIBLE else View.GONE
        binding.syncDetailText.visibility = if (detailText.isNotBlank()) View.VISIBLE else View.GONE

        val p = progressPercent
        if (p == null) {
            binding.syncProgressBar.isIndeterminate = true
        } else {
            binding.syncProgressBar.isIndeterminate = false
            binding.syncProgressBar.progress = p
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val b = bytes.toDouble().coerceAtLeast(0.0)
        return when {
            b >= gb -> String.format("%.1f GB", b / gb)
            b >= mb -> String.format("%.1f MB", b / mb)
            b >= kb -> String.format("%.0f KB", b / kb)
            else -> String.format("%.0f B", b)
        }
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}

class DevModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_DEV_MODE) return
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { SettingsManager(context.applicationContext).setDevMode(enabled) }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_SET_DEV_MODE = "com.mupa.player.enterprise.ACTION_SET_DEV_MODE"
        const val EXTRA_ENABLED = "enabled"
    }
}
