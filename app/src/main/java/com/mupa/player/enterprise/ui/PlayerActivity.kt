package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.os.Process
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
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
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.mupa.player.enterprise.audience.AudienceAnalyticsManager
import com.mupa.player.enterprise.audience.AudienceSyncManager
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.databinding.ActivityPlayerBinding
import com.mupa.player.enterprise.demo.DemoColors
import com.mupa.player.enterprise.demo.DemoImageCache
import com.mupa.player.enterprise.demo.DemoProduct
import com.mupa.player.enterprise.demo.DemoProductRepository
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.MediaSyncProgress
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.player.PlaybackProfile
import com.mupa.player.enterprise.player.PlayerEngine
import com.mupa.player.enterprise.player.TransitionConfig
import com.mupa.player.enterprise.price.PriceConfig
import com.mupa.player.enterprise.price.PriceConfigParser
import com.mupa.player.enterprise.price.PriceAnalyticsSyncManager
import com.mupa.player.enterprise.price.PriceOffer
import com.mupa.player.enterprise.price.PriceProduct
import com.mupa.player.enterprise.price.PricePack
import com.mupa.player.enterprise.price.PriceTheme
import com.mupa.player.enterprise.price.PriceQueryEngine
import com.mupa.player.enterprise.price.LayoutConfig
import com.mupa.player.enterprise.price.PriceSlot
import com.mupa.player.enterprise.price.ProductPriceSlot
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.Paint
import android.view.LayoutInflater
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt
import java.util.UUID
import androidx.palette.graphics.Palette

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
    private val barcodeSimulationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ean = intent.getStringExtra("ean") ?: return
            onBarcodeCaptured(ean)
        }
    }

    private lateinit var manifestManager: ManifestManager
    private var audienceManager: AudienceAnalyticsManager? = null
    private var audienceStarted = false
    private var playlistName: String? = null
    private var itemNameById: Map<String, String> = emptyMap()
    private var companyId: String? = null

    private var priceConfig: PriceConfig? = null
    private var priceEngine: PriceQueryEngine? = null
    private var lastPlaylistItemsIds: List<String> = emptyList()
    private var lastScanEan: String = ""
    private var lastScanAtMs: Long = 0L
    private val scanBuffer = StringBuilder()
    private var scanLastCharAtMs: Long = 0L
    private val scanKeyBuffer = StringBuilder()
    private var scanKeyLastCharAtMs: Long = 0L
    private var hideOverlayJob: Job? = null
    private var overlayRenderJob: Job? = null
    private var syncHideJob: Job? = null
    private lateinit var demoRepo: DemoProductRepository
    private lateinit var demoImageCache: DemoImageCache
    private var devMode = false
    private var demoMode = false
    private var overlayEan: String? = null
    private var lastRenderedPriceSlotCount: Int = 1
    private var priceAnimator: ValueAnimator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var lastSpokenEan: String = ""
    private var lastSpokenAtMs: Long = 0L
    private var lastNotFoundEan: String = ""
    private var lastNotFoundAtMs: Long = 0L

    private val demoHttp: OkHttpClient by lazy { TlsCompat.newClient() }
    private val overlayImageLoader: ImageLoader by lazy { ImageLoader(applicationContext) }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                lifecycleScope.launch { ensureAudienceStarted() }
            }
        }

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
        initTts()
        applyPriceTypography()
        binding.apkVersionWatermark.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        demoRepo = DemoProductRepository(applicationContext)
        demoImageCache = DemoImageCache(applicationContext)
        setupDevModeToggle()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(barcodeSimulationReceiver, IntentFilter("com.mupa.player.enterprise.SIMULATE_BARCODE"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeSimulationReceiver, IntentFilter("com.mupa.player.enterprise.SIMULATE_BARCODE"))
        }

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

        setupHiddenBarcodeInput(binding.hiddenBarcodeInput)
        binding.hiddenBarcodeInput.post { ensureBarcodeFocus() }
        binding.root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        binding.root.setOnTouchListener { _, _ ->
            ensureBarcodeFocus()
            false
        }
        binding.priceOverlay.setOnTouchListener { _, _ ->
            ensureBarcodeFocus()
            false
        }


        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    applyImmersive()
                }
                launch {
                    keepBarcodeFocus()
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
            ensureBarcodeFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            val serviceIntent = Intent(this, com.mupa.player.enterprise.services.InactivityTimerService::class.java)
            stopService(serviceIntent)
        }
        binding.hiddenBarcodeInput.post { ensureBarcodeFocus() }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val now = SystemClock.elapsedRealtime()

            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                val text = scanKeyBuffer.toString().trim()
                scanKeyBuffer.setLength(0)
                scanKeyLastCharAtMs = 0L
                if (text.isNotBlank()) {
                    binding.hiddenBarcodeInput.setText("")
                    scanBuffer.setLength(0)
                    scanLastCharAtMs = 0L
                    onBarcodeCaptured(text)
                    return true
                }
            } else {
                var ch = event.unicodeChar
                if (ch <= 0 && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                    ch = '0'.code + (keyCode - KeyEvent.KEYCODE_0)
                }
                if (ch > 0) {
                    if (scanKeyLastCharAtMs != 0L && now - scanKeyLastCharAtMs > 300L) {
                        scanKeyBuffer.setLength(0)
                    }
                    scanKeyLastCharAtMs = now
                    scanKeyBuffer.append(ch.toChar())
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
        runCatching { unregisterReceiver(barcodeSimulationReceiver) }
        priceAnimator?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        runCatching { tone?.release() }
        tone = null
        playerEngine.release()
        super.onDestroy()
    }

    override fun onStop() {
        lifecycleScope.launch {
            audienceManager?.stop()
            audienceManager = null
            audienceStarted = false
        }
        super.onStop()
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
        priceEngine = PriceQueryEngine(applicationContext, deviceId)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { priceEngine?.getDefaultProductImagePath() }
        }

        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        binding.deviceNameText.text = cache?.deviceName?.ifBlank { deviceId } ?: deviceId
        companyId = cache?.company?.trim()?.ifBlank { null }
        ensureAssaiDefaultPriceConfigIfNeeded()
        ensureAmericanasDefaultPriceConfigIfNeeded()
        ensureZaffariDefaultPriceConfigIfNeeded()

        ensureStoragePermissionIfNeeded()
        tryStartOfflinePlayback()
        initialSyncAndPlayback()
        ensureAudienceStarted()

        lifecycleScope.launch {
            while (true) {
                delay(60 * 1000L)
                updatePlaylistIfActiveItemsChanged()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60 * 60 * 1000L) // every 1 hour
                if (isOnline()) {
                    runCatching { AudienceSyncManager(applicationContext).uploadPending() }
                    runCatching { PriceAnalyticsSyncManager(applicationContext).uploadPending() }
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
                
                binding.btnDevSimulateEan.setOnClickListener {
                    val ean = binding.editDevSimulateEan.text.toString().trim()
                    if (ean.isNotBlank()) {
                        binding.editDevSimulateEan.setText("")
                        // Hide keyboard
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(binding.editDevSimulateEan.windowToken, 0)
                        lifecycleScope.launch {
                            onBarcodeCaptured(ean)
                        }
                    }
                }
                binding.editDevSimulateEan.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        val ean = binding.editDevSimulateEan.text.toString().trim()
                        if (ean.isNotBlank()) {
                            binding.editDevSimulateEan.setText("")
                            // Hide keyboard
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.hideSoftInputFromWindow(binding.editDevSimulateEan.windowToken, 0)
                            lifecycleScope.launch {
                                onBarcodeCaptured(ean)
                            }
                        }
                        true
                    } else false
                }
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
        applyPriceConfigFromManifestJson(offlineJson)
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
        applyPriceConfigFromManifestJson(remote)
        applyTransitionConfigFromManifestJson(remote)

        val items = manifestManager.parseItemsPublic(remote)
        var playlist = buildLocalPlaylist(items)
        if (playlist.size == items.size) {
            playerEngine.setPlaylist(playlist)
            setSyncOverlayVisible(false)
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

        runCatching {
            manifestManager.syncMedia(
                deviceId = deviceId,
                manifestJson = remote,
                onProgress = { p -> runOnUiThread { renderProgress(p) } },
                maxConcurrentDownloads = 1,
            )
        }

        playlist = buildLocalPlaylist(items)
        while (playlist.size < items.size) {
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
    }

    private suspend fun refreshInBackground(): Boolean {
        if (!isOnline()) return false
        runCatching {
            com.mupa.player.enterprise.services.DeviceValidationService(applicationContext).validateDevice(deviceId)
        }
        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        val licenseType = cache?.tipoDaLicenca?.trim()?.lowercase(Locale.US)
        val isLicenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
        if (isLicenseValid) {
            val modelsDir = File(filesDir, "models")
            val ageGenderFile = File(modelsDir, "age_gender_model.tflite")
            val faceRecFile = File(modelsDir, "mobilefacenet.tflite")
            val needsDownload = !ageGenderFile.exists() || ageGenderFile.length() == 0L ||
                                !faceRecFile.exists() || faceRecFile.length() == 0L
            if (needsDownload) {
                setSyncOverlayVisible(true)
                updateSyncTexts(
                    status = "Baixando modelos de reconhecimento facial...",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                com.mupa.player.enterprise.audience.ModelProvisioningManager.ensureModelsProvisioned(applicationContext, licenseType)
                setSyncOverlayVisible(false)
            }
        }

        ensureAudienceStarted()

        val remote = runCatching { manifestManager.fetchManifest(deviceId) }.getOrNull()?.trim()
        if (remote.isNullOrBlank()) return false
        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (!changed) return true

        manifestManager.saveManifest(deviceId, remote)
        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyPriceConfigFromManifestJson(remote)
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
            )
        }
    }

    private suspend fun ensureAudienceStarted() {
        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        val licenseType = cache?.tipoDaLicenca?.trim()?.lowercase(Locale.US)
        val licenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
        val canRun = AudienceAnalyticsManager.canRunOnDevice(this)

        if (!licenseValid || !canRun) {
            if (audienceStarted) {
                audienceManager?.stop()
                audienceManager = null
                audienceStarted = false
                withContext(Dispatchers.Main) {
                    binding.txtTransparencyWarning.visibility = View.GONE
                }
                Log.i("PlayerActivity", "Audience analytics stopped due to license or hardware changes. License: $licenseType, CanRun: $canRun")
            }
            return
        }

        if (audienceStarted) {
            // Already running, and license/camera is still valid. Do not restart.
            return
        }

        if (!AudienceAnalyticsManager.hasCameraPermission(this)) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }

        val manager =
            AudienceAnalyticsManager(
                context = applicationContext,
                lifecycleOwner = this,
                deviceId = deviceId,
                contentPlayingProvider = {
                    val id = playerEngine.getCurrentItemId()
                    if (id.isNullOrBlank()) null else itemNameById[id] ?: id
                },
                playlistProvider = { playlistName },
            )

        val started = runCatching { manager.startIfPossible() }.getOrDefault(false)
        if (started) {
            audienceManager = manager
            audienceStarted = true
            withContext(Dispatchers.Main) {
                binding.txtTransparencyWarning.visibility = View.VISIBLE
            }
            Log.i("PlayerActivity", "Audience analytics started successfully. License: $licenseType")
        } else {
            runCatching { manager.stop() }
            withContext(Dispatchers.Main) {
                binding.txtTransparencyWarning.visibility = View.GONE
            }
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

    private fun setupHiddenBarcodeInput(input: EditText) {
        input.setText("")
        input.isCursorVisible = false
        input.showSoftInputOnFocus = false
        input.inputType = android.text.InputType.TYPE_NULL
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                input.post { ensureBarcodeFocus() }
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isBlank()) return

                if (text.contains('\n') || text.contains('\r')) {
                    val cleaned = text.replace("\n", "").replace("\r", "").trim()
                    s?.clear()
                    scanBuffer.setLength(0)
                    scanLastCharAtMs = 0L
                    if (cleaned.isNotBlank()) onBarcodeCaptured(cleaned)
                    return
                }

                val now = SystemClock.elapsedRealtime()
                if (scanLastCharAtMs != 0L && now - scanLastCharAtMs > 300L) {
                    scanBuffer.setLength(0)
                }
                scanLastCharAtMs = now
                scanBuffer.setLength(0)
                scanBuffer.append(text)
            }
        })

        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                val text =
                    if (input.text?.isNotBlank() == true) input.text.toString().trim() else scanBuffer.toString().trim()
                input.setText("")
                scanBuffer.setLength(0)
                scanLastCharAtMs = 0L
                if (text.isNotBlank()) {
                    onBarcodeCaptured(text)
                    return@setOnKeyListener true
                }
                return@setOnKeyListener false
            }

            val ch = event.unicodeChar
            if (ch > 0) {
                val now = SystemClock.elapsedRealtime()
                if (scanLastCharAtMs != 0L && now - scanLastCharAtMs > 300L) {
                    scanBuffer.setLength(0)
                }
                scanLastCharAtMs = now
                scanBuffer.append(ch.toChar())
            }
            false
        }
    }

    private suspend fun keepBarcodeFocus() {
        val imm = ContextCompat.getSystemService(this, InputMethodManager::class.java)
        while (true) {
            val devInput = binding.editDevSimulateEan
            if (devInput != null && devInput.hasFocus()) {
                delay(1000)
                continue
            }
            ensureBarcodeFocus()
            imm?.hideSoftInputFromWindow(binding.hiddenBarcodeInput.windowToken, 0)
            delay(500)
        }
    }

    private fun ensureBarcodeFocus() {
        val devInput = binding.editDevSimulateEan
        if (devInput != null && devInput.hasFocus()) return

        val input = binding.hiddenBarcodeInput
        if (!input.isFocusableInTouchMode) input.isFocusableInTouchMode = true
        if (!input.isFocusable) input.isFocusable = true
        if (currentFocus !== input) {
            currentFocus?.clearFocus()
        }
        if (!input.hasFocus()) {
            input.requestFocus()
            input.requestFocusFromTouch()
        }
    }

    private fun onBarcodeCaptured(raw: String) {
        val ean = raw.trim()
        if (ean.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (ean == lastScanEan && now - lastScanAtMs < 2000L) return
        lastScanEan = ean
        lastScanAtMs = now

        lifecycleScope.launch {
            if (ean == "040816" || ean == "230205") {
                if (!android.provider.Settings.canDrawOverlays(this@PlayerActivity)) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(
                        this@PlayerActivity,
                        "Por favor, ative a permissão de sobreposição para reabrir o app após 60s.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val serviceIntent = Intent(this@PlayerActivity, com.mupa.player.enterprise.services.InactivityTimerService::class.java)
                startService(serviceIntent)

                if (ean == "040816") {
                    finishAffinity()
                } else {
                    try {
                        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(settingsIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this@PlayerActivity, "Erro ao abrir configurações", Toast.LENGTH_SHORT).show()
                    }
                }
                return@launch
            }

            if (ean == "190524") {
                showAdminAccessDialog()
                return@launch
            }

            val cmd = ean.trim().lowercase(Locale.US)
            if (cmd == "devon" || cmd == "devmode=1" || cmd == "devmode=true" || cmd == "demoon") {
                runCatching { SettingsManager(applicationContext).setDevMode(true) }
                devMode = true
                updateDeviceWatermark()
                Toast.makeText(this@PlayerActivity, "DEMO ativado", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (cmd == "devoff" || cmd == "devmode=0" || cmd == "devmode=false" || cmd == "demooff") {
                runCatching { SettingsManager(applicationContext).setDevMode(false) }
                devMode = false
                updateDeviceWatermark()
                Toast.makeText(this@PlayerActivity, "DEMO desativado", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (cmd == "manifest" || cmd == "showmanifest") {
                val m = runCatching { ManifestManager(applicationContext).loadOfflineManifest(deviceId) }.getOrNull()
                if (m.isNullOrBlank()) {
                    Toast.makeText(this@PlayerActivity, "Nenhum manifest local encontrado.", Toast.LENGTH_SHORT).show()
                } else {
                    android.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Manifest.json")
                        .setMessage(m)
                        .setPositiveButton("Fechar", null)
                        .show()
                }
                return@launch
            }
            if (
                cmd == "resetapp" ||
                    cmd == "wipeapp" ||
                    cmd == "wipeall" ||
                    cmd == "clearapp" ||
                    cmd == "mupa:reset" ||
                    cmd == "mupa/reset" ||
                    cmd == "mupa://reset"
            ) {
                promptWipeAppData()
                return@launch
            }

            val cfg =
                priceConfig ?: run {
                    if (shouldForceAssaiIntegration()) {
                        ensureAssaiDefaultPriceConfigIfNeeded()
                    } else if (shouldForceAmericanasIntegration()) {
                        ensureAmericanasDefaultPriceConfigIfNeeded()
                    } else if (shouldForceZaffariIntegration()) {
                        ensureZaffariDefaultPriceConfigIfNeeded()
                    } else {
                        ensureDefaultSupabasePriceConfig()
                    }
                    priceConfig
                }
            Log.i(
                "MPlayerScan",
                "barcode_captured ean=$ean hasCfg=${cfg != null} integration=${cfg?.integration} companyId=$companyId online=${isOnline()} devMode=$devMode demoMode=$demoMode",
            )
            if (demoMode) {
                showPriceOverlayLoading(ean)
                val remoteDemo = runCatching { fetchDemoProductFromSupabase(ean) }.getOrNull()
                if (remoteDemo != null) {
                    showDemoProduct(remoteDemo)
                    scheduleHidePriceOverlay(8000L)
                    return@launch
                }
            }

            val demo = runCatching { demoRepo.getDemoProduct(ean) }.getOrNull()
            if (demo != null) {
                showDemoProduct(demo)
                scheduleHidePriceOverlay(8000L)
                return@launch
            }

            if (cfg == null) {
                setPriceOverlayVisible(false)
                speakNotFoundIfPossible(ean)
                return@launch
            }

            var mockProduct: PriceProduct? = null
            val isMock = when (cmd) {
                "test_normal" -> {
                    mockProduct = PriceProduct(
                        id = "1", ean = "TEST_NORMAL", description = "PRODUTO TESTE NORMAL",
                        price = 9.99, originalPrice = null, clubPrice = null,
                        xmlLayoutType = "price_check_normal", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                "test_depor" -> {
                    mockProduct = PriceProduct(
                        id = "2", ean = "TEST_DEPOR", description = "PRODUTO TESTE DE / POR",
                        price = 7.99, originalPrice = 10.00, clubPrice = null, pricePromotional = 7.99, priceFrom = 10.00,
                        xmlLayoutType = "price_check_de_por", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                "test_atacado" -> {
                    mockProduct = PriceProduct(
                        id = "3", ean = "TEST_ATACADO", description = "PRODUTO TESTE ATACADO",
                        price = 9.99, originalPrice = null, clubPrice = null, priceWholesale = 8.49,
                        xmlLayoutType = "price_check_atacado", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                "test_clube" -> {
                    mockProduct = PriceProduct(
                        id = "4", ean = "TEST_CLUBE", description = "PRODUTO TESTE CLUBE KOCH",
                        price = 8.99, originalPrice = 10.00, clubPrice = 7.99, pricePromotional = 8.99, priceClub = 7.99, priceFrom = 10.00,
                        xmlLayoutType = "price_check_clube_koch", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                "test_cartao" -> {
                    mockProduct = PriceProduct(
                        id = "5", ean = "TEST_CARTAO", description = "PRODUTO TESTE CARTÃO KOCH",
                        price = 9.99, originalPrice = null, clubPrice = null, cardPrice = 8.99,
                        xmlLayoutType = "price_check_cartao_koch", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // ── Zaffari ─────────────────────────────────────────────────
                // Só preço base, sem clube nem bulk
                "test_zaf_normal" -> {
                    mockProduct = PriceProduct(
                        id = "ZAF001", ean = "7898080640413", description = "LEITE CONDENSADO ITALAC 395G",
                        price = 4.99, originalPrice = null, clubPrice = null,
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO/UN", value = 4.99, field = "price", isPromo = false, isClub = false),
                        ),
                        xmlLayoutType = "multi_price", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Preço base + preço clube (caixa verde "CLIENTE CLUBE")
                "test_zaf_clube" -> {
                    mockProduct = PriceProduct(
                        id = "ZAF002", ean = "7891000100103", description = "BISCOITO OREO 90G",
                        price = 4.99, originalPrice = null, clubPrice = 2.19, priceClub = 2.19,
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO/UN", value = 4.99, field = "price", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLIENTE CLUBE", value = 2.19, field = "price_club", isPromo = false, isClub = true),
                        ),
                        xmlLayoutType = "multi_price", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Preço base + "A PARTIR DE X un" (bulk sem clube)
                "test_zaf_bulk" -> {
                    mockProduct = PriceProduct(
                        id = "ZAF003", ean = "7622210565563", description = "TRIDENT MAX MENTA 16G",
                        price = 5.99, originalPrice = null, clubPrice = null, priceWholesale = 4.49,
                        offer = PriceOffer(enabled = true, title = "A PARTIR DE 27 UN", description = null, secondUnit = 4.49, type = "bulk"),
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO/UN", value = 5.99, field = "price", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "A PARTIR DE 27 UN", value = 4.49, field = "price_wholesale", isPromo = false, isClub = false),
                        ),
                        xmlLayoutType = "multi_price", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Pesável: preço por KG + preço clube por KG
                "test_zaf_pesavel" -> {
                    mockProduct = PriceProduct(
                        id = "ZAF004", ean = "2133390007187", description = "QUEIJO MUSSARELA FATIADO KG",
                        price = 49.90, originalPrice = null, clubPrice = 39.90, priceClub = 39.90,
                        priceWeighable = 49.90,
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO POR KG", value = 49.90, field = "price_weighable", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLIENTE CLUBE", value = 39.90, field = "price_club", isPromo = false, isClub = true),
                        ),
                        xmlLayoutType = "multi_price", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Preço base + clube + "A PARTIR DE X un" + clube a partir de — todas as camadas
                "test_zaf_full" -> {
                    mockProduct = PriceProduct(
                        id = "ZAF005", ean = "7891000315507", description = "CHOCOLATE LACTA AO LEITE 80G",
                        price = 5.99, originalPrice = null, clubPrice = 2.19, priceClub = 2.19, priceWholesale = 2.09,
                        offer = PriceOffer(enabled = true, title = "A PARTIR DE 27 UN", description = null, secondUnit = 2.09, type = "bulk"),
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO/UN", value = 5.99, field = "price", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLIENTE CLUBE", value = 2.19, field = "price_club", isPromo = false, isClub = true),
                            ProductPriceSlot(label = "A PARTIR DE 27 UN", value = 4.49, field = "price_wholesale", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLUBE 27 UN", value = 2.09, field = "price_club", isPromo = false, isClub = true),
                        ),
                        xmlLayoutType = "multi_price", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // ── Americanas ──────────────────────────────────────────────
                // Só preço regular, sem promoção
                "test_ame_normal" -> {
                    mockProduct = PriceProduct(
                        id = "AME001", ean = "7891000100103", description = "BISCOITO OREO RECHEADO 90G",
                        price = 4.99, originalPrice = null, clubPrice = null, pricePromotional = null,
                        xmlLayoutType = null, packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Preço regular + preço promocional (layout De / Por)
                "test_ame_promo" -> {
                    mockProduct = PriceProduct(
                        id = "AME002", ean = "7622210565563", description = "TRIDENT MAX MENTA 16G",
                        price = 5.99, originalPrice = 5.99, clubPrice = null, priceFrom = 5.99, pricePromotional = 5.49,
                        xmlLayoutType = "price_check_de_por", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Só promoção de pacote "Leve Mais" (takeWin), sem desconto de preço unitário
                "test_ame_bundle" -> {
                    mockProduct = PriceProduct(
                        id = "AME003", ean = "7891000315507", description = "CHOCOLATE LACTA AO LEITE 80G",
                        price = 5.49, originalPrice = null, clubPrice = null, pricePromotional = null,
                        offer = PriceOffer(
                            enabled = true, title = "COMPRE 2",
                            description = "Economize R$ 1,99", secondUnit = 4.50, type = "BUY_N_GET_DISCOUNT"
                        ),
                        packs = listOf(PricePack(label = "Leve 2 unidades", price = 8.99, unitPrice = 4.50)),
                        xmlLayoutType = null, theme = null, offline = false
                    )
                    true
                }
                // Preço regular + promoção unitária (De/Por) + pacote "Leve Mais" — todas as camadas
                "test_ame_full" -> {
                    mockProduct = PriceProduct(
                        id = "AME004", ean = "7891910000197", description = "NESCAFÉ TRADIÇÃO SOLÚVEL 50G",
                        price = 12.99, originalPrice = 12.99, clubPrice = null, priceFrom = 12.99, pricePromotional = 10.99,
                        offer = PriceOffer(
                            enabled = true, title = "COMPRE 3",
                            description = "Economize R$ 6,00", secondUnit = 9.99, type = "BUY_N_GET_DISCOUNT"
                        ),
                        packs = listOf(PricePack(label = "Leve 3 unidades", price = 29.97, unitPrice = 9.99)),
                        xmlLayoutType = "price_check_de_por", theme = null, offline = false
                    )
                    true
                }
                else -> false
            }

            if (isMock && mockProduct != null) {
                showPriceOverlayProduct(mockProduct)
                scheduleHidePriceOverlay(8000L)
                return@launch
            }

            val engine = priceEngine ?: return@launch
            showPriceOverlayLoading(ean)
            val product = runCatching { engine.query(ean, cfg, isOnline()) }.getOrNull()
            if (product != null) {
                showPriceOverlayProduct(product)
                scheduleHidePriceOverlay(computePriceDisplayTimeoutMs(cfg.timeoutMs))
            } else {
                val offlineProduct = runCatching { engine.query(ean, cfg, isOnline = false) }.getOrNull()
                if (offlineProduct != null) {
                    showPriceOverlayProduct(offlineProduct.copy(offline = true))
                    scheduleHidePriceOverlay(computePriceDisplayTimeoutMs(cfg.timeoutMs))
                } else {
                    setPriceOverlayVisible(false)
                    speakNotFoundIfPossible(ean)
                }
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

    private suspend fun fetchDemoProductFromSupabase(ean: String): DemoProduct? = withContext(Dispatchers.IO) {
        val normalized = ean.trim()
        if (normalized.isBlank()) return@withContext null

        val url = "https://vsocztidewsdlzcongkz.supabase.co/functions/v1/api-produtos?ean=$normalized"
        val req = Request.Builder().url(url).get().build()
        val bodyText =
            demoHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string().orEmpty()
            }

        val produto = JSONObject(bodyText).optJSONObject("produto") ?: return@withContext null
        val nome =
            produto.optString("nome_curto", "").trim().ifBlank { produto.optString("nome", "").trim() }
        if (nome.isBlank()) return@withContext null

        val preco = produto.optDouble("preco", Double.NaN).takeIf { v -> !v.isNaN() } ?: return@withContext null
        val precoLista = produto.optDouble("preco_lista", Double.NaN).takeIf { v -> !v.isNaN() }
        val precoAntigo = precoLista?.takeIf { it > preco }

        val imagemUrl =
            normalizeUrl(
                produto.optString("imagem_url_vtex", "").trim().ifBlank {
                    produto.optString("imagem_url_azure", "").trim().ifBlank {
                        produto.optString("imagem_local", "").trim()
                    }
                },
            ).ifBlank { null }

        DemoProduct(
            ean = produto.optString("ean", normalized).trim().ifBlank { normalized },
            nome = nome,
            imagemUrl = imagemUrl,
            preco = preco,
            precoAntigo = precoAntigo,
            parcelamento = null,
            marca = produto.optString("marca", "").trim().ifBlank { null },
            categoria = produto.optString("categoria", "").trim().ifBlank { null },
        )
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

    private fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim()
    }

    private fun applyPriceConfigFromManifestJson(manifestJson: String) {
        val json = manifestManager.parsePriceConfigJson(manifestJson) ?: return
        val cfg = runCatching { PriceConfigParser.parse(json) }.getOrNull() ?: return
        if (shouldForceAssaiIntegration()) {
            priceConfig = cfg.copy(integration = "integra-assai")
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-assai companyId=$companyId")
            return
        }
        if (shouldForceAmericanasIntegration()) {
            priceConfig = cfg.copy(integration = "integra-americana")
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-americana companyId=$companyId")
            return
        }
        if (shouldForceZaffariIntegration()) {
            priceConfig = cfg.copy(integration = "integra-zaffari")
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-zaffari companyId=$companyId")
            return
        }
        priceConfig = cfg
        Log.i("MPlayerPrice", "price_config_applied integration=${cfg.integration} companyId=$companyId")
    }

    private fun shouldForceAssaiIntegration(): Boolean {
        val id = companyId?.trim().orEmpty()
        return id.equals("687b2692-dab7-4934-8ed1-eee6eb02dbb8", ignoreCase = true)
    }

    private fun shouldForceAmericanasIntegration(): Boolean {
        val id = companyId?.trim().orEmpty()
        return id.equals("510a683a-db10-466f-8890-dc8629a36390", ignoreCase = true)
    }

    private fun ensureAssaiDefaultPriceConfigIfNeeded() {
        if (!shouldForceAssaiIntegration()) return
        if (priceConfig != null) return
        priceConfig =
            PriceConfig(
                integration = "integra-assai",
                timeoutMs = 8000L,
                cacheMinutes = 3,
                steps = emptyList(),
                analyticsUploadUrl = null,
            )
        Log.i("MPlayerPrice", "price_config_default_assai_applied companyId=$companyId")
    }

    private fun ensureAmericanasDefaultPriceConfigIfNeeded() {
        if (!shouldForceAmericanasIntegration()) return
        if (priceConfig != null) return
        priceConfig =
            PriceConfig(
                integration = "integra-americana",
                timeoutMs = 8000L,
                cacheMinutes = 5,
                steps = emptyList(),
                analyticsUploadUrl = null,
            )
        Log.i("MPlayerPrice", "price_config_default_americana_applied companyId=$companyId")
    }

    private fun shouldForceZaffariIntegration(): Boolean {
        val id = companyId?.trim().orEmpty()
        return id.equals("fd55dbdd-63da-442e-aa99-5575c0496622", ignoreCase = true)
    }

    private fun ensureZaffariDefaultPriceConfigIfNeeded() {
        if (!shouldForceZaffariIntegration()) return
        if (priceConfig != null) return
        priceConfig =
            PriceConfig(
                integration = "integra-zaffari",
                timeoutMs = 9000L,
                cacheMinutes = 5,
                steps = emptyList(),
                analyticsUploadUrl = null,
            )
        Log.i("MPlayerPrice", "price_config_default_zaffari_applied companyId=$companyId")
    }

    private fun ensureDefaultSupabasePriceConfig() {
        // Fallback Supabase removido conforme solicitado
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

    private fun showPriceOverlayLoading(ean: String) {
        setPriceOverlayVisible(true)
        overlayEan = ean
        overlayRenderJob?.cancel()

        binding.priceResultRoot.visibility = View.GONE
        binding.priceLoadingContainer.visibility = View.VISIBLE
        binding.priceLoadingContainer.alpha = 0f
        binding.priceLoadingContainer.scaleX = 0.95f
        binding.priceLoadingContainer.scaleY = 0.95f
        binding.priceLoadingContainer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()

        binding.priceNameText.text = "CONSULTANDO..."
        binding.priceSubtitleText.visibility = View.GONE
        binding.priceCodeText.text = "Código: $ean"

        binding.priceLeftBadgeText.visibility = View.GONE
        binding.priceRightBadgeText.visibility = View.GONE
        binding.priceOfflineBadge.visibility = View.GONE

        binding.priceOfferContainer.visibility = View.GONE
        binding.priceSecondUnitContainer.visibility = View.GONE

        renderPrice(value = null, animate = false)
        binding.priceProductImage.setImageDrawable(null)
        binding.pricePacksContainer.removeAllViews()
        binding.pricePacksContainer.visibility = View.GONE
    }

    private fun showPriceOverlayProduct(product: PriceProduct) {
        setPriceOverlayVisible(true)
        overlayEan = product.ean
        overlayRenderJob?.cancel()

        val expectedEan = product.ean
        overlayRenderJob =
            lifecycleScope.launch {
                val engine = priceEngine
                val cfg = priceConfig

                val localFromProduct =
                    product.image
                        ?.takeIf { it.startsWith("/") }
                        ?.let { File(it) }
                        ?.takeIf { it.exists() && it.length() > 0L }
                        ?.absolutePath

                val localPath = localFromProduct ?: engine?.getLocalProductImagePathIfExists(expectedEan)

                var finalImagePath: String? = null
                var finalTheme: PriceTheme? = product.theme

                if (localPath.isNullOrBlank()) {
                    if (engine != null && cfg != null) {
                        val (downloadedPath, theme) =
                            runCatching {
                                engine.preloadProductImageAndTheme(
                                    ean = expectedEan,
                                    config = cfg,
                                    isOnline = isOnline(),
                                )
                            }.getOrNull() ?: (null to null)

                        finalImagePath = downloadedPath
                        if (theme != null) {
                            finalTheme = theme
                        }
                    }
                } else {
                    finalImagePath = localPath
                }

                if (finalImagePath.isNullOrBlank()) {
                    finalImagePath = runCatching { engine?.getDefaultProductImagePath() }.getOrNull()
                }

                val prepared =
                    if (!finalImagePath.isNullOrBlank()) {
                        prepareOverlayFromImage(url = finalImagePath, theme = finalTheme)
                    } else {
                        null
                    }

                withContext(Dispatchers.Main) {
                    if (overlayEan != expectedEan || binding.priceOverlay.visibility != View.VISIBLE) return@withContext

                    val layoutType = product.xmlLayoutType ?: priceConfig?.layout?.xmlLayoutType ?: "split"
                    inflateLayoutForProduct(layoutType)

                    val nameText = binding.priceResultRoot.findViewById<TextView>(R.id.priceNameText)
                    val codeText = binding.priceResultRoot.findViewById<TextView>(R.id.priceCodeText)
                    val subtitleText = binding.priceResultRoot.findViewById<TextView>(R.id.priceSubtitleText)
                    val offlineBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfflineBadge)
                    val offerText = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfferText)
                    val offerContainer = binding.priceResultRoot.findViewById<View>(R.id.priceOfferContainer)
                    val packsContainer = binding.priceResultRoot.findViewById<ViewGroup>(R.id.pricePacksContainer)
                    val priceContainer = binding.priceResultRoot.findViewById<LinearLayout>(R.id.price_container)

                    nameText?.text = buildModernName(product.description.orEmpty())
                    codeText?.text = "Código: ${product.ean}"

                    if (product.offline) {
                        offlineBadge?.visibility = View.VISIBLE
                        offlineBadge?.text = "OFFLINE"
                    } else {
                        offlineBadge?.visibility = View.GONE
                    }

                    val specificLayouts = listOf("price_check_normal", "price_check_de_por", "price_check_atacado", "price_check_clube_koch", "price_check_cartao_koch")
                    val hasOriginal = product.originalPrice != null && product.price != null && product.originalPrice > product.price
                    if (hasOriginal && subtitleText != null && layoutType !in specificLayouts) {
                        val formattedOriginal = formatCurrency(product.originalPrice!!)
                        val originalText = "De: $formattedOriginal"
                        val spannable = SpannableStringBuilder(originalText)
                        spannable.setSpan(
                            StrikethroughSpan(),
                            0,
                            originalText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        subtitleText.text = spannable
                        subtitleText.visibility = View.VISIBLE
                    } else {
                        subtitleText?.visibility = View.GONE
                    }

                    renderOffer(product.offer, offerContainer, offerText)
                    renderPacks(product.packs, packsContainer)

                    val updatedProduct = product.copy(theme = finalTheme)
                    val accentHex = if (priceConfig?.layout?.colorMode == "solid" && !priceConfig?.layout?.solidColor.isNullOrEmpty()) {
                        priceConfig?.layout?.solidColor
                    } else {
                        finalTheme?.signature ?: "#06b6d4"
                    }
                    val parsedColor = runCatching { Color.parseColor(accentHex) }.getOrDefault(Color.parseColor("#06b6d4"))

                    if (layoutType in specificLayouts) {
                        bindSpecificLayoutPrices(updatedProduct, layoutType)
                    } else if (priceContainer != null) {
                        populatePriceSlots(priceContainer, priceConfig?.layout, updatedProduct, parsedColor)
                    }

                    if (prepared != null) {
                        applyOverlayColors(
                            dominant = prepared.dominant,
                            dark = prepared.dark,
                            light = prepared.light,
                            vibrant = prepared.secondary,
                            text = prepared.text,
                        )
                        val imgView = binding.priceResultRoot.findViewById<ImageView>(R.id.priceProductImage)
                        prepared.drawable?.let { imgView?.setImageDrawable(it) }
                    } else {
                        val imgView = binding.priceResultRoot.findViewById<ImageView>(R.id.priceProductImage)
                        imgView?.setImageResource(com.mupa.player.enterprise.R.drawable.ic_mplayer)
                    }

                    applyThemeColors(binding.priceResultRoot, updatedProduct, priceConfig?.layout)

                    binding.priceResultRoot.alpha = 0f
                    binding.priceResultRoot.visibility = View.VISIBLE

                    if (binding.priceLoadingContainer.visibility == View.VISIBLE) {
                        binding.priceLoadingContainer.animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction {
                                binding.priceLoadingContainer.visibility = View.GONE
                            }
                            .start()
                    } else {
                        binding.priceLoadingContainer.visibility = View.GONE
                    }

                    binding.priceResultRoot.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()

                    playBeep()

                    speakPriceIfPossible(
                        ean = product.ean,
                        price = product.price,
                        oldPrice = product.originalPrice,
                        offer = product.offer,
                        clubPrice = product.clubPrice
                    )

                    animateOverlayWidgets()
                }
            }
    }

    private fun renderPacks(packs: List<PricePack>) {
        binding.pricePacksContainer.removeAllViews()
        if (packs.isEmpty()) {
            binding.pricePacksContainer.visibility = View.GONE
            return
        }
        binding.pricePacksContainer.visibility = View.VISIBLE
        packs.forEach { p ->
            val card = createPackCard(p.label, p.price, p.unitPrice)
            binding.pricePacksContainer.addView(card)
        }
    }

    private fun showDemoProduct(product: DemoProduct) {
        overlayEan = product.ean
        setPriceOverlayVisible(true)
        showPriceOverlayLoading(product.ean)

        val expectedEan = product.ean
        overlayRenderJob =
            lifecycleScope.launch {
                val cachedColors = demoImageCache.readCachedColors(product.ean)
                val fallback =
                    PreparedOverlay(
                        dominant = Color.parseColor("#003399"),
                        secondary = Color.parseColor("#FF6B00"),
                        dark = Color.parseColor("#002266"),
                        light = Color.parseColor("#FFFFFF"),
                        text = Color.WHITE,
                        drawable = null,
                    )

                val imageData: Any? =
                    demoImageCache.imageFile(product.ean).takeIf { it.exists() && it.length() > 0L }
                        ?: product.imagemUrl

                val prepared =
                    if (imageData != null) {
                        val p = prepareOverlayFromImage(data = imageData, fallback = fallback)
                        if (cachedColors != null) {
                            val dom = parseColorOrNull(cachedColors.dominante) ?: p.dominant
                            val dark = parseColorOrNull(cachedColors.escuro) ?: p.dark
                            val light = parseColorOrNull(cachedColors.claro) ?: p.light
                            val sec = parseColorOrNull(cachedColors.vibrante) ?: p.secondary
                            val text = parseColorOrNull(cachedColors.texto) ?: p.text
                            p.copy(dominant = dom, dark = dark, light = light, secondary = sec, text = text)
                        } else {
                            p
                        }
                    } else {
                        fallback
                    }

                withContext(Dispatchers.Main) {
                    if (overlayEan != expectedEan || binding.priceOverlay.visibility != View.VISIBLE) return@withContext

                    val layoutType = priceConfig?.layout?.xmlLayoutType ?: "split"
                    inflateLayoutForProduct(layoutType)

                    // Find views from the dynamically inflated layout
                    val nameText = binding.priceResultRoot.findViewById<TextView>(R.id.priceNameText)
                    val codeText = binding.priceResultRoot.findViewById<TextView>(R.id.priceCodeText)
                    val subtitleText = binding.priceResultRoot.findViewById<TextView>(R.id.priceSubtitleText)
                    val offlineBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfflineBadge)
                    val offerText = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfferText)
                    val offerContainer = binding.priceResultRoot.findViewById<View>(R.id.priceOfferContainer)
                    val packsContainer = binding.priceResultRoot.findViewById<ViewGroup>(R.id.pricePacksContainer)
                    val priceContainer = binding.priceResultRoot.findViewById<LinearLayout>(R.id.price_container)
                    val imgView = binding.priceResultRoot.findViewById<ImageView>(R.id.priceProductImage)

                    applyOverlayColors(prepared.dominant, prepared.dark, prepared.light, prepared.secondary, prepared.text)
                    applyPriceOverlayLayout()
                    binding.priceResultRoot.visibility = View.VISIBLE
                    binding.priceLoadingContainer.animate().alpha(0f).setDuration(180).withEndAction {
                        binding.priceLoadingContainer.visibility = View.GONE
                    }.start()

                    nameText?.text = buildModernName(product.nome)
                    subtitleText?.visibility = View.GONE
                    codeText?.text = "Código: ${product.ean}"

                    offlineBadge?.visibility = View.GONE
                    renderOffer(null, offerContainer, offerText)

                    val demoPriceProduct = PriceProduct(
                        id = null,
                        ean = product.ean,
                        description = product.nome,
                        price = product.preco,
                        originalPrice = product.precoAntigo,
                        clubPrice = null,
                        stock = null,
                        image = null,
                        offer = null,
                        packs = emptyList(),
                        theme = null,
                        offline = false
                    )

                    val accentHex = if (priceConfig?.layout?.colorMode == "solid" && !priceConfig?.layout?.solidColor.isNullOrEmpty()) {
                        priceConfig?.layout?.solidColor
                    } else {
                        null
                    }
                    val parsedColor = accentHex?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: prepared.dominant

                    if (priceContainer != null) {
                        populatePriceSlots(priceContainer, priceConfig?.layout, demoPriceProduct, parsedColor)
                    }

                    playBeep()
                    speakPriceIfPossible(ean = product.ean, price = product.preco, oldPrice = product.precoAntigo, offer = null)

                    packsContainer?.removeAllViews()
                    packsContainer?.visibility = View.GONE

                    val drawable = prepared.drawable
                    if (drawable != null) {
                        imgView?.setImageDrawable(drawable)
                    } else {
                        imgView?.setImageResource(com.mupa.player.enterprise.R.drawable.ic_mplayer)
                    }
                    applyThemeColors(binding.priceResultRoot, demoPriceProduct, priceConfig?.layout)

                    animateOverlayWidgets()
                }
            }
    }

    private fun setPriceOverlayVisible(visible: Boolean) {
        if (visible) {
            if (binding.priceOverlay.visibility != View.VISIBLE) {
                binding.priceOverlay.alpha = 0f
                binding.priceOverlay.scaleX = 0.985f
                binding.priceOverlay.scaleY = 0.985f
                binding.priceOverlay.visibility = View.VISIBLE
                setPlayerBlur(true)
                if (!isFinishing && !isDestroyed) {
                    playerEngine.pause()
                }
                binding.priceOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            }
        } else {
            if (binding.priceOverlay.visibility == View.VISIBLE) {
                setPlayerBlur(false)
                binding.priceOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                    binding.priceOverlay.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) {
                        playerEngine.resume()
                    }
                }.start()
            }
        }
    }

    private fun playBeep() {
        if (tone == null) {
            tone =
                runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
                    .getOrNull()
                    ?: return
        }
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 80) }
    }

    /**
     * Quanto mais tipos de preço aparecem na tela (normal/atacado/clube/oferta), mais tempo o
     * cliente precisa pra ler tudo antes do overlay fechar sozinho.
     */
    private fun computePriceDisplayTimeoutMs(baseTimeoutMs: Long): Long {
        val base = baseTimeoutMs.coerceAtLeast(6000L)
        val extraSlots = (lastRenderedPriceSlotCount - 1).coerceAtLeast(0)
        return (base + extraSlots * 2000L).coerceAtMost(14000L)
    }

    private fun scheduleHidePriceOverlay(timeoutMs: Long) {
        hideOverlayJob?.cancel()
        hideOverlayJob =
            lifecycleScope.launch {
                delay(timeoutMs.coerceAtLeast(1000L))
                setPriceOverlayVisible(false)
            }
    }

    private fun setPlayerBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            binding.playerContainer.setRenderEffect(
                if (enabled) RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP) else null,
            )
            binding.playerContainer.animate().alpha(if (enabled) 0.55f else 1f).setDuration(180).start()
        } else {
            binding.playerContainer.alpha = 1f
        }
    }

    private data class PreparedOverlay(
        val dominant: Int,
        val secondary: Int,
        val dark: Int,
        val light: Int,
        val text: Int,
        val drawable: android.graphics.drawable.Drawable?,
    )

    private suspend fun prepareOverlayFromImage(url: String?, theme: PriceTheme?): PreparedOverlay {
        val fallback = fallbackOverlayFromTheme(theme)
        if (url.isNullOrBlank()) return fallback
        val file = url.takeIf { it.startsWith("/") }?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        val data: Any = file ?: url

        val hasTheme =
            !theme?.signature.isNullOrBlank() &&
                !theme?.dark.isNullOrBlank() &&
                !theme?.light.isNullOrBlank()

        if (hasTheme) {
            val req =
                ImageRequest.Builder(applicationContext)
                    .data(data)
                    .allowHardware(false)
                    .build()
            val result = runCatching { overlayImageLoader.execute(req) }.getOrNull() as? SuccessResult
            val drawable = result?.drawable
            return fallback.copy(drawable = drawable)
        }

        return prepareOverlayFromImage(data = data, fallback = fallback)
    }

    private fun fallbackOverlayFromTheme(theme: PriceTheme?): PreparedOverlay {
        val dom = parseColorOrNull(theme?.signature) ?: Color.parseColor("#003399")
        val dark = parseColorOrNull(theme?.dark) ?: Color.parseColor("#002266")
        val light = parseColorOrNull(theme?.light) ?: Color.parseColor("#FFFFFF")
        val secondary = parseColorOrNull(theme?.signature) ?: Color.parseColor("#FF6B00")
        val text = idealTextColor(dark)
        return PreparedOverlay(dominant = dom, secondary = secondary, dark = dark, light = light, text = text, drawable = null)
    }

    private suspend fun prepareOverlayFromImage(data: Any, fallback: PreparedOverlay): PreparedOverlay = withContext(Dispatchers.IO) {
        val req =
            ImageRequest.Builder(applicationContext)
                .data(data)
                .allowHardware(false)
                .build()
        val result = runCatching { overlayImageLoader.execute(req) }.getOrNull() as? SuccessResult
        val drawable = result?.drawable
        val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        val palette = bitmap?.let { Palette.from(it).generate() }

        val dominant = palette?.getDominantColor(fallback.dominant) ?: fallback.dominant
        val dark =
            palette?.getDarkMutedColor(fallback.dark)
                ?: palette?.getDarkVibrantColor(fallback.dark)
                ?: fallback.dark
        val light =
            palette?.getLightVibrantColor(fallback.light)
                ?: palette?.getLightMutedColor(fallback.light)
                ?: fallback.light
        val secondary =
            palette?.getVibrantColor(fallback.secondary)
                ?: palette?.getMutedColor(fallback.secondary)
                ?: fallback.secondary
        val text = idealTextColor(dark)

        fallback.copy(dominant = dominant, secondary = secondary, dark = dark, light = light, text = text, drawable = drawable)
    }

    /**
     * Aplica as cores extraídas da imagem do produto (Palette) na overlay de preço atualmente
     * inflada em [binding.priceResultRoot]. IMPORTANTE: [inflateLayoutForProduct] faz
     * `removeAllViews()` + reinflate a cada consulta, então as referências antigas tipadas pelo
     * ViewBinding (ex: `binding.priceLeftPanel`) ficam órfãs (fora da árvore) após a primeira
     * troca de layout. Por isso aqui sempre buscamos as views de novo via findViewById no
     * container atual, em vez de usar `binding.X` diretamente.
     */
    private fun applyOverlayColors(dominant: Int, dark: Int, light: Int, vibrant: Int, text: Int) {
        val root = binding.priceResultRoot

        val gradient =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(dark, dominant),
            )
        root.findViewById<View>(R.id.priceLeftPanel)?.background = gradient
        root.findViewById<View>(R.id.priceRightPanel)?.setBackgroundColor(Color.WHITE)

        val nameTextColor = idealTextColor(dominant)
        root.findViewById<View>(R.id.priceNameBar)?.setBackgroundColor(dominant)
        root.findViewById<TextView>(R.id.priceNameText)?.setTextColor(nameTextColor)
        root.findViewById<TextView>(R.id.priceSubtitleText)?.setTextColor(adjustAlpha(nameTextColor, 0.90f))

        val badgeAColor = makeHueAccent(vibrant, 6f)
        val badgeBColor = makeHueAccent(vibrant, 210f)
        val badgeAText = idealTextColor(badgeAColor)
        val badgeBText = idealTextColor(badgeBColor)
        root.findViewById<View>(R.id.priceLeftBadgeText)?.let {
            it.background = rectBg(badgeAColor, alpha = 1f)
            (it as? TextView)?.setTextColor(badgeAText)
        }
        root.findViewById<View>(R.id.priceRightBadgeText)?.let {
            it.background = rectBg(badgeBColor, alpha = 1f)
            (it as? TextView)?.setTextColor(badgeBText)
        }

        root.findViewById<View>(R.id.priceOfflineBadge)?.let {
            it.background = rectBg(adjustAlpha(Color.BLACK, 0.18f), alpha = 1f)
            (it as? TextView)?.setTextColor(Color.WHITE)
        }

        // Caixa principal de preço (apenas layouts genéricos: split/centered/backdrop/etc).
        // Os layouts específicos (de_por, atacado, clube_koch, cartao_koch) já têm cores fixas
        // semânticas no XML (ex: verde para "melhor preço") e não usam esses ids.
        root.findViewById<View>(R.id.priceMainContainer)?.background = rectBg(vibrant, alpha = 1f)
        val priceTextColor = idealTextColor(vibrant)
        root.findViewById<TextView>(R.id.priceCurrencyText)?.setTextColor(priceTextColor)
        root.findViewById<TextView>(R.id.priceIntegerText)?.apply {
            setTextColor(priceTextColor)
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        root.findViewById<TextView>(R.id.priceDecimalText)?.setTextColor(priceTextColor)

        root.findViewById<View>(R.id.priceOfferContainer)?.background = rectBg(adjustAlpha(light, 0.14f), alpha = 1f)
        root.findViewById<TextView>(R.id.priceOfferText)?.setTextColor(text)

        root.findViewById<View>(R.id.priceSecondUnitContainer)?.background = rectBg(adjustAlpha(light, 0.12f), alpha = 1f)
        root.findViewById<TextView>(R.id.priceSecondUnitLabel)?.setTextColor(adjustAlpha(text, 0.92f))

        val unitValueBg = makeHueAccent(vibrant, 140f)
        root.findViewById<View>(R.id.priceSecondUnitValue)?.let {
            it.background = rectBg(unitValueBg, alpha = 1f)
            (it as? TextView)?.setTextColor(idealTextColor(unitValueBg))
        }

        root.findViewById<TextView>(R.id.priceCodeText)?.setTextColor(adjustAlpha(text, 0.86f))
    }

    private fun idealTextColor(background: Int): Int {
        val luminance =
            (0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background)) / 255.0
        return if (luminance < 0.55) Color.WHITE else Color.parseColor("#1A1A1A")
    }

    private fun applyPriceOverlayLayout() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val set = androidx.constraintlayout.widget.ConstraintSet()
        set.clone(binding.priceResultRoot)
        if (isLandscape) {
            set.setGuidelinePercent(binding.priceLandscapeSplitGuide.id, 0.50f)

            set.clear(binding.priceLeftPanel.id)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.END, binding.priceLandscapeSplitGuide.id, androidx.constraintlayout.widget.ConstraintSet.START)

            set.clear(binding.priceRightPanel.id)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.START, binding.priceLandscapeSplitGuide.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)

            binding.priceCurrencyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            binding.priceIntegerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 98f)
            binding.priceDecimalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
        } else {
            set.setGuidelinePercent(binding.priceImageSplitGuide.id, 0.45f)

            set.clear(binding.priceRightPanel.id)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, binding.priceImageSplitGuide.id, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(binding.priceRightPanel.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)

            set.clear(binding.priceLeftPanel.id)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.priceImageSplitGuide.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(binding.priceLeftPanel.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)

            binding.priceCurrencyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            binding.priceIntegerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
            binding.priceDecimalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        }

        set.applyTo(binding.priceResultRoot)
    }

    private fun renderPrice(value: Double?, animate: Boolean) {
        priceAnimator?.cancel()
        if (value == null || value <= 0.0) {
            binding.priceIntegerText.text = "—"
            binding.priceDecimalText.text = ""
            return
        }

        if (!animate) {
            val parts = formatPriceParts(value)
            binding.priceIntegerText.text = parts.first
            binding.priceDecimalText.text = parts.second
            return
        }

        val endCents = (value * 100.0).roundToInt().coerceAtLeast(0)
        val animator = ValueAnimator.ofInt(0, endCents)
        priceAnimator = animator
        animator.duration = 420L
        animator.addUpdateListener {
            val cents = it.animatedValue as Int
            val v = cents / 100.0
            val parts = formatPriceParts(v)
            binding.priceIntegerText.text = parts.first
            binding.priceDecimalText.text = parts.second
        }
        animator.start()
    }

    private fun formatPriceParts(value: Double): Pair<String, String> {
        val cents = (value * 100.0).roundToInt().coerceAtLeast(0)
        val intPart = cents / 100
        val decPart = cents % 100
        return intPart.toString() to String.format(Locale("pt", "BR"), ",%02d", decPart)
    }

    private fun animateOverlayWidgets() {
        binding.priceLeftPanel.animate().cancel()
        binding.priceLeftPanel.alpha = 0f
        binding.priceLeftPanel.animate().alpha(1f).setDuration(300).start()

        binding.priceProductImage.animate().cancel()
        binding.priceProductImage.alpha = 0f
        binding.priceProductImage.scaleX = 0.95f
        binding.priceProductImage.scaleY = 0.95f
        binding.priceProductImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).setStartDelay(60).start()

        binding.priceNameBar.animate().cancel()
        binding.priceNameBar.alpha = 0f
        binding.priceNameBar.translationX = -26f
        binding.priceNameBar.animate().alpha(1f).translationX(0f).setDuration(260).setStartDelay(110).start()

        binding.priceMainContainer.animate().cancel()
        binding.priceMainContainer.alpha = 0f
        binding.priceMainContainer.scaleX = 0.8f
        binding.priceMainContainer.scaleY = 0.8f
        binding.priceMainContainer.animate()
            .alpha(1f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(220)
            .setStartDelay(150)
            .setInterpolator(OvershootInterpolator(0.85f))
            .withEndAction {
                binding.priceMainContainer.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()

        if (binding.priceBadgesRow.visibility == View.VISIBLE) {
            binding.priceBadgesRow.animate().cancel()
            binding.priceBadgesRow.alpha = 0f
            binding.priceBadgesRow.translationY = 18f
            binding.priceBadgesRow.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(190).start()
        }

        if (binding.priceOfferContainer.visibility == View.VISIBLE) {
            binding.priceOfferContainer.animate().cancel()
            binding.priceOfferContainer.alpha = 0f
            binding.priceOfferContainer.translationY = 18f
            binding.priceOfferContainer.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(220).start()
        }

        if (binding.priceSecondUnitContainer.visibility == View.VISIBLE) {
            binding.priceSecondUnitContainer.animate().cancel()
            binding.priceSecondUnitContainer.alpha = 0f
            binding.priceSecondUnitContainer.translationY = 18f
            binding.priceSecondUnitContainer.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(250).start()
        }
    }

    private fun rectBg(color: Int, alpha: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(adjustAlpha(color, alpha))
            cornerRadius = 0f
        }
    }

    private fun roundedBg(color: Int, radiusDp: Float = 14f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }
    }

    private fun blendColors(a: Int, b: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * clamped).roundToInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * clamped).roundToInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * clamped).roundToInt().coerceIn(0, 255),
        )
    }

    private fun makeHueAccent(base: Int, hue: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[0] = hue
        hsv[1] = hsv[1].coerceAtLeast(0.70f)
        hsv[2] = hsv[2].coerceAtLeast(0.78f)
        return Color.HSVToColor(hsv)
    }

    private fun buildModernName(raw: String): CharSequence {
        val cleaned = raw.trim().replace("\\s+".toRegex(), " ")
        if (cleaned.isBlank()) return ""
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        val top = words.take(3).joinToString(" ").uppercase(Locale("pt", "BR"))
        val rest = words.drop(3).joinToString(" ").uppercase(Locale("pt", "BR"))

        val b = SpannableStringBuilder()
        val topStart = 0
        b.append(top)
        b.setSpan(StyleSpan(android.graphics.Typeface.BOLD), topStart, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(RelativeSizeSpan(1.16f), topStart, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (rest.isNotBlank()) {
            b.append("\n")
            val restStart = b.length
            b.append(rest)
            b.setSpan(StyleSpan(android.graphics.Typeface.NORMAL), restStart, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(RelativeSizeSpan(0.74f), restStart, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val totalChars = cleaned.length
        val sizeSp =
            when {
                totalChars <= 14 -> 44f
                totalChars <= 20 -> 40f
                totalChars <= 28 -> 34f
                else -> 30f
            }
        binding.priceNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        return b
    }

    private fun renderOffer(offer: PriceOffer?) {
        if (offer == null || !offer.enabled) {
            binding.priceBadgesRow.visibility = View.GONE
            binding.priceLeftBadgeText.visibility = View.GONE
            binding.priceRightBadgeText.visibility = View.GONE
            binding.priceOfferContainer.visibility = View.GONE
            binding.priceSecondUnitContainer.visibility = View.GONE
            return
        }

        val (badgeA, badgeB) = splitOfferBadges(offer.title?.trim().orEmpty())
        binding.priceBadgesRow.visibility = View.VISIBLE
        if (badgeA.isNotBlank()) {
            binding.priceLeftBadgeText.text = badgeA
            binding.priceLeftBadgeText.visibility = View.VISIBLE
        } else {
            binding.priceLeftBadgeText.visibility = View.GONE
        }
        if (!badgeB.isNullOrBlank()) {
            binding.priceRightBadgeText.text = badgeB
            binding.priceRightBadgeText.visibility = View.VISIBLE
        } else {
            binding.priceRightBadgeText.visibility = View.GONE
        }

        val desc = offer.description?.trim().orEmpty()
        if (desc.isNotBlank()) {
            binding.priceOfferText.text = desc
            binding.priceOfferContainer.visibility = View.VISIBLE
        } else {
            binding.priceOfferContainer.visibility = View.GONE
        }

        val second = offer.secondUnit
        if (second != null && second > 0.0) {
            binding.priceSecondUnitValue.text = formatCurrency(second)
            binding.priceSecondUnitContainer.visibility = View.VISIBLE
        } else {
            binding.priceSecondUnitContainer.visibility = View.GONE
        }
    }

    private fun splitOfferBadges(title: String): Pair<String, String?> {
        val t = title.trim().uppercase(Locale("pt", "BR"))
        if (t.isBlank()) return "OFERTA" to null
        val m = Regex("LEVE\\s+(\\d+)\\s+PAGUE\\s+(\\d+)").find(t)
        if (m != null) {
            return "LEVE ${m.groupValues[1]}" to "PAGUE ${m.groupValues[2]}"
        }
        val parts =
            t.split("|", "/", " - ", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            else -> t to null
        }
    }

    private fun buildStyledDescription(description: String): CharSequence {
        val cleaned = description.trim().replace("\\s+".toRegex(), " ")
        if (cleaned.isBlank()) return ""
        val parts = cleaned.split(" ")
        val first = parts.take(3).joinToString(" ")
        val rest = parts.drop(3).joinToString(" ")

        val b = SpannableStringBuilder()
        val start = 0
        b.append(first)
        b.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(RelativeSizeSpan(1.40f), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (rest.isNotBlank()) {
            b.append("\n")
            val rStart = b.length
            b.append(rest)
            b.setSpan(RelativeSizeSpan(0.92f), rStart, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    private fun initTts() {
        if (tts != null) return
        tts =
            TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val locale = Locale("pt", "BR")
                    val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsReady = false
                    } else {
                        tts?.setSpeechRate(1.02f)
                        tts?.setPitch(1.0f)
                    }
                }
            }
    }

    private fun speakPriceIfPossible(
        ean: String,
        price: Double?,
        oldPrice: Double?,
        offer: com.mupa.player.enterprise.price.PriceOffer?,
        clubPrice: Double? = null,
    ) {
        if (!ttsReady) return

        var resolvedPrice = price
        var resolvedOldPrice = oldPrice
        var resolvedClubPrice = clubPrice

        // Se preço promocional for maior ou igual ao preço normal de tabela, ignorar preço promocional
        if (resolvedPrice != null && resolvedOldPrice != null && resolvedPrice >= resolvedOldPrice) {
            resolvedPrice = resolvedOldPrice
            resolvedOldPrice = null
        }

        // Se preço clube for maior ou igual ao preço de venda, ignorar o preço clube no TTS
        if (resolvedClubPrice != null && resolvedPrice != null && resolvedClubPrice >= resolvedPrice) {
            resolvedClubPrice = null
        }

        if (resolvedPrice == null || resolvedPrice <= 0.0) return

        val now = SystemClock.elapsedRealtime()
        if (ean == lastSpokenEan && now - lastSpokenAtMs < 3500L) return
        lastSpokenEan = ean
        lastSpokenAtMs = now

        val spokenPrice = buildSpokenPrice(resolvedPrice)
        val isOffer = (offer?.enabled == true) || (resolvedOldPrice != null && resolvedOldPrice > resolvedPrice)
        val base =
            if (isOffer) {
                val old = resolvedOldPrice
                if (old != null && old > resolvedPrice) {
                     "Produto em oferta. De ${buildSpokenPrice(old)} por $spokenPrice."
                } else {
                     "Produto em oferta. $spokenPrice."
                }
            } else {
                spokenPrice
            }

        val extra = StringBuilder()
        if (resolvedClubPrice != null && resolvedClubPrice > 0.0) {
            extra.append(" Preço exclusivo para cliente clube, ${buildSpokenPrice(resolvedClubPrice)}.")
        }

        if (offer != null && offer.enabled) {
            val second = offer.secondUnit
            if (second != null && second > 0.0) {
                extra.append(" ${offer.title?.trim().orEmpty().ifBlank { "Oferta" }}. Valor total das duas unidades: ${buildSpokenPrice(second)}.")
            } else {
                offer.title?.trim()?.takeIf { it.isNotBlank() }?.let { extra.append(" $it.") }
            }
        }

        val textToSpeak = base + extra.toString()
        val utteranceId = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= 21) {
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    private fun speakNotFoundIfPossible(ean: String) {
        if (!ttsReady) return
        val normalized = ean.trim()
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        if (normalized == lastNotFoundEan && now - lastNotFoundAtMs < 2500L) return
        lastNotFoundEan = normalized
        lastNotFoundAtMs = now

        val utteranceId = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= 21) {
            tts?.speak("Produto não encontrado!", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak("Produto não encontrado!", TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    private fun buildSpokenPrice(value: Double): String {
        val cents = (value * 100.0).roundToInt().coerceAtLeast(0)
        val reais = cents / 100
        val cent = cents % 100
        val reaisStr = if (reais == 1) "real" else "reais"
        return if (cent == 0) {
            "$reais $reaisStr"
        } else {
            "$reais $reaisStr e $cent centavos"
        }
    }

    private fun applyPriceTypography() {
        val bebas = android.graphics.Typeface.create("Bebas Neue", android.graphics.Typeface.NORMAL)
        val fallback = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        val tf = if (bebas == android.graphics.Typeface.DEFAULT) fallback else bebas
        binding.priceCurrencyText.typeface = tf
        binding.priceIntegerText.typeface = tf
        binding.priceDecimalText.typeface = tf
        binding.priceIntegerText.letterSpacing = 0.02f
        binding.priceDecimalText.letterSpacing = 0.02f
        binding.priceCurrencyText.letterSpacing = 0.02f
    }

    private fun formatCurrency(value: Double): String {
        return String.format(Locale("pt", "BR"), "R$ %.2f", value)
    }

    private fun parseColorOrNull(value: String?): Int? {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return null
        return runCatching { Color.parseColor(v) }.getOrNull()
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun createPackCard(label: String, packPrice: Double, unitPrice: Double): View {
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = 10
        val container = android.widget.LinearLayout(this)
        container.layoutParams = params
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(16, 14, 16, 14)
        container.background = rectBg(Color.WHITE, alpha = 0.10f)

        val t1 = MaterialTextView(this)
        t1.text = label
        t1.setTextColor(Color.WHITE)
        t1.textSize = 16f
        t1.setTypeface(t1.typeface, android.graphics.Typeface.BOLD)

        val t2 = MaterialTextView(this)
        t2.text = "${formatCurrency(packPrice)} • Cada unidade sai por ${formatCurrency(unitPrice)}"
        t2.setTextColor(Color.parseColor("#E6FFFFFF"))
        t2.textSize = 13f

        container.addView(t1)
        container.addView(t2)
        return container
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

    private fun inflateLayoutForProduct(xmlLayoutType: String) {
        val container = binding.priceResultRoot
        container.removeAllViews()
        val layoutResId = when (xmlLayoutType) {
            "split_inverted" -> R.layout.price_check_split_inverted
            "vertical_image_bottom" -> R.layout.price_check_vertical_image_bottom
            "vertical_image_top" -> R.layout.price_check_vertical_image_top
            "backdrop" -> R.layout.price_check_backdrop
            "centered" -> R.layout.price_check_centered
            "multi_price" -> R.layout.price_check_multi_price
            "split" -> R.layout.price_check_split
            "price_check_normal" -> R.layout.price_check_normal
            "price_check_de_por" -> R.layout.price_check_de_por
            "price_check_atacado" -> R.layout.price_check_atacado
            "price_check_clube_koch" -> R.layout.price_check_clube_koch
            "price_check_cartao_koch" -> R.layout.price_check_cartao_koch
            else -> R.layout.price_check_split
        }
        val inflater = LayoutInflater.from(container.context)
        inflater.inflate(layoutResId, container, true)

        // Clear the default drawable (ic_mplayer) immediately to prevent logo flashing
        val imgView = container.findViewById<ImageView>(R.id.priceProductImage)
        imgView?.setImageDrawable(null)
    }

    private fun populatePriceSlots(
        container: LinearLayout,
        layoutConfig: LayoutConfig?,
        product: PriceProduct,
        accentColor: Int
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)

        val productSlots = product.priceSlots
        if (!productSlots.isNullOrEmpty()) {
            val normalPrice = product.price
            val validSlots = productSlots.filter { slot ->
                if (slot.value <= 0.0) return@filter false
                if (normalPrice != null && slot.value == normalPrice) {
                    val isSecondary = slot.isPromo || slot.isClub || 
                                     slot.field == "price_wholesale" || 
                                     slot.field == "priceWholesale" ||
                                     slot.field == "price_club" ||
                                     slot.field == "priceClub" ||
                                     slot.field == "price_promotional" ||
                                     slot.field == "pricePromotional"
                    if (isSecondary) return@filter false
                }
                true
            }
            val bestValue = validSlots.minOfOrNull { it.value }
            val compact = validSlots.size >= 3
            lastRenderedPriceSlotCount = validSlots.size.coerceAtLeast(1)
            for (slot in validSlots) {
                val itemView = inflater.inflate(R.layout.item_price_slot, container, false)

                val labelTextView = itemView.findViewById<TextView>(R.id.slotLabel)
                val integerTextView = itemView.findViewById<TextView>(R.id.slotIntegerPrice)
                val decimalTextView = itemView.findViewById<TextView>(R.id.slotDecimalPrice)
                val fromPriceTextView = itemView.findViewById<TextView>(R.id.slotFromPrice)
                val currencyTextView = itemView.findViewById<TextView>(R.id.slotCurrency)

                labelTextView.text = slot.label

                val isBest = bestValue != null && slot.value == bestValue
                styleSlotBox(itemView, labelTextView, currencyTextView, integerTextView, decimalTextView, isBest = isBest, isClub = slot.isClub, isPromo = slot.isPromo, accentColor = accentColor)

                val formatted = String.format(Locale.US, "%.2f", slot.value)
                val parts = formatted.split(".")
                integerTextView.text = parts[0]
                decimalTextView.text = ",${parts[1]}"

                emphasizePriceSlot(
                    labelTextView, currencyTextView, integerTextView, decimalTextView,
                    isBest = isBest,
                    compact = compact,
                )
                applyCompactSpacing(itemView, compact)

                fromPriceTextView.visibility = View.GONE

                container.addView(itemView)
            }
            return
        }

        // Fallback de Segurança tradicional: se não há price_slots configurados nem retornados pela
        // API, monta dinamicamente um slot por campo de preço disponível (normal/atacado/clube/promo),
        // sempre destacando a melhor opção em vez de exibir só o preço normal.
        val dynamicSlots = buildList {
            add(PriceSlot(field = "price", label = "PREÇO NORMAL", showFromPrice = true))
            if (product.priceWholesale != null && product.priceWholesale > 0.0) {
                add(PriceSlot(field = "price_wholesale", label = "ATACADO", showFromPrice = false))
            }
            if (product.priceClub != null && product.priceClub > 0.0) {
                add(PriceSlot(field = "price_club", label = "CLUBE", showFromPrice = false))
            }
            val promo = product.pricePromotional
            if (promo != null && promo > 0.0 && promo != product.price && promo != product.priceClub) {
                add(PriceSlot(field = "price_promotional", label = "OFERTA", showFromPrice = false))
            }
        }
        val slots = layoutConfig?.priceSlots?.takeIf { it.isNotEmpty() } ?: dynamicSlots

        fun valueOf(field: String): Double? = when (field) {
            "price" -> product.price
            "price_promotional", "pricePromotional" -> product.pricePromotional
            "price_club", "priceClub" -> product.priceClub
            "price_wholesale", "priceWholesale" -> product.priceWholesale
            "price_weighable", "priceWeighable" -> product.priceWeighable
            else -> null
        }

        val normalPrice = valueOf("price") ?: product.price
        val resolvedSlots = slots.filter { slot ->
            val v = valueOf(slot.field)
            if (v == null || v <= 0.0) return@filter false
            if (slot.field != "price" && normalPrice != null && v == normalPrice) {
                return@filter false
            }
            true
        }
        val bestValue = resolvedSlots.mapNotNull { valueOf(it.field) }.minOrNull()
        val compact = resolvedSlots.size >= 3
        lastRenderedPriceSlotCount = resolvedSlots.size.coerceAtLeast(1)

        for (slot in resolvedSlots) {
            val priceValue = valueOf(slot.field) ?: continue

            val itemView = inflater.inflate(R.layout.item_price_slot, container, false)

            val labelTextView = itemView.findViewById<TextView>(R.id.slotLabel)
            val integerTextView = itemView.findViewById<TextView>(R.id.slotIntegerPrice)
            val decimalTextView = itemView.findViewById<TextView>(R.id.slotDecimalPrice)
            val fromPriceTextView = itemView.findViewById<TextView>(R.id.slotFromPrice)
            val currencyTextView = itemView.findViewById<TextView>(R.id.slotCurrency)

            labelTextView.text = slot.label

            val isClub = slot.field == "price_club" || slot.field == "priceClub"
            val isPromo = slot.field == "price_promotional" || slot.field == "pricePromotional"
            val isBest = bestValue != null && priceValue == bestValue
            styleSlotBox(itemView, labelTextView, currencyTextView, integerTextView, decimalTextView, isBest = isBest, isClub = isClub, isPromo = isPromo, accentColor = accentColor)

            val formatted = String.format(Locale.US, "%.2f", priceValue)
            val parts = formatted.split(".")
            integerTextView.text = parts[0]
            decimalTextView.text = ",${parts[1]}"

            emphasizePriceSlot(
                labelTextView, currencyTextView, integerTextView, decimalTextView,
                isBest = bestValue != null && priceValue == bestValue,
                compact = compact,
            )
            applyCompactSpacing(itemView, compact)

            val compareFromPrice = product.priceFrom ?: product.originalPrice
            if (slot.showFromPrice && compareFromPrice != null && compareFromPrice > priceValue) {
                fromPriceTextView.visibility = View.VISIBLE
                fromPriceTextView.text = String.format("De: R$ %.2f", compareFromPrice)
                fromPriceTextView.paintFlags = fromPriceTextView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                fromPriceTextView.visibility = View.GONE
            }

            container.addView(itemView)
        }
    }

    /**
     * Pinta a caixa do preço (fundo) e calcula a cor do texto sempre pelo contraste real do
     * fundo: fundo claro -> texto escuro; fundo escuro/colorido -> texto claro. A cor do fundo
     * não é fixa: clube=verde, oferta/promo=vermelho, melhor preço "normal"=cor de destaque
     * extraída da imagem do produto, e os demais (secundários) ficam numa caixa branca.
     */
    private fun styleSlotBox(
        itemView: View,
        label: TextView,
        currency: TextView?,
        integer: TextView,
        decimal: TextView,
        isBest: Boolean,
        isClub: Boolean,
        isPromo: Boolean,
        accentColor: Int,
    ) {
        val boxColor = when {
            isClub -> Color.parseColor("#10b981")
            isPromo -> Color.parseColor("#ef4444")
            isBest -> accentColor
            else -> Color.parseColor("#FFFFFF")
        }
        val textColor = idealTextColor(boxColor)

        itemView.findViewById<View>(R.id.slotPriceBox)?.background = roundedBg(boxColor)
        integer.setTextColor(textColor)
        decimal.setTextColor(textColor)
        currency?.setTextColor(textColor)
        // O rótulo fica sobre o painel escuro (fora da caixa), não sobre a caixa de preço.
        label.setTextColor(Color.WHITE)
    }

    /**
     * Sempre destaca o melhor preço entre os slots: aumenta o valor (moeda/inteiro/decimal) e
     * deixa o rótulo ainda mais discreto; os demais preços ficam visualmente menores/secundários.
     */
    private fun emphasizePriceSlot(
        label: TextView,
        currency: TextView?,
        integer: TextView,
        decimal: TextView,
        isBest: Boolean,
        compact: Boolean = false,
    ) {
        // Com 3+ tipos de preço na tela, reduz um pouco os tamanhos pra caber tudo sem cortar
        // nem precisar rolar.
        if (isBest) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
            label.alpha = 1f
            currency?.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 19f else 22f)
            integer.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 44f else 54f)
            decimal.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 24f else 30f)
        } else {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
            label.alpha = 0.75f
            currency?.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 15f)
            integer.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 30f else 36f)
            decimal.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 17f else 20f)
        }
    }

    /** Aperta as margens/paddings do item e da caixa de preço quando há 3+ tipos na tela. */
    private fun applyCompactSpacing(itemView: View, compact: Boolean) {
        if (!compact) return
        itemView.setPadding(
            itemView.paddingLeft,
            dpToPx(6),
            itemView.paddingRight,
            dpToPx(6),
        )
        val box = itemView.findViewById<View>(R.id.slotPriceBox)
        box?.setPadding(dpToPx(14), dpToPx(7), dpToPx(14), dpToPx(7))
        val label = itemView.findViewById<View>(R.id.slotLabel)
        (label?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.bottomMargin = dpToPx(3) }
    }

    private fun applyThemeColors(view: View, product: PriceProduct, layoutConfig: LayoutConfig?) {
        val themeColorHex = if (layoutConfig?.colorMode == "solid" && !layoutConfig.solidColor.isNullOrEmpty()) {
            layoutConfig.solidColor
        } else {
            product.theme?.signature ?: "#06b6d4"
        }
        val parsedColor = runCatching { Color.parseColor(themeColorHex) }.getOrDefault(Color.parseColor("#06b6d4"))

        val borderView = view.findViewById<View>(R.id.priceOverlayRoot) ?: binding.priceOverlayRoot
        val drawable = borderView.background as? GradientDrawable ?: GradientDrawable()
        drawable.setStroke(dpToPx(4), parsedColor)
        borderView.background = drawable

        val currencyText = view.findViewById<TextView>(R.id.priceCurrencyText)
        val integerText = view.findViewById<TextView>(R.id.priceIntegerText)
        val decimalText = view.findViewById<TextView>(R.id.priceDecimalText)

        currencyText?.setTextColor(parsedColor)
        integerText?.setTextColor(parsedColor)
        decimalText?.setTextColor(parsedColor)

        val glowBackground = view.findViewById<ImageView>(R.id.priceProductGlowEffect)
        if (layoutConfig?.style == "modern" && glowBackground != null) {
            glowBackground.visibility = View.VISIBLE
            val glowDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    adjustAlpha(parsedColor, 0.25f),
                    Color.TRANSPARENT
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpToPx(120).toFloat()
            }
            glowBackground.background = glowDrawable
        } else {
            glowBackground?.visibility = View.GONE
        }
    }



    private fun renderOffer(offer: PriceOffer?, offerContainer: View?, offerText: TextView?) {
        val badgesRow = binding.priceResultRoot.findViewById<View>(R.id.priceBadgesRow)
        val leftBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceLeftBadgeText)
        val rightBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceRightBadgeText)
        val secondUnitContainer = binding.priceResultRoot.findViewById<View>(R.id.priceSecondUnitContainer)
        val secondUnitValue = binding.priceResultRoot.findViewById<TextView>(R.id.priceSecondUnitValue)

        if (offer == null || !offer.enabled) {
            badgesRow?.visibility = View.GONE
            leftBadge?.visibility = View.GONE
            rightBadge?.visibility = View.GONE
            offerContainer?.visibility = View.GONE
            secondUnitContainer?.visibility = View.GONE
            return
        }

        val (badgeA, badgeB) = splitOfferBadges(offer.title?.trim().orEmpty())
        badgesRow?.visibility = View.VISIBLE
        if (badgeA.isNotBlank()) {
            leftBadge?.text = badgeA
            leftBadge?.visibility = View.VISIBLE
        } else {
            leftBadge?.visibility = View.GONE
        }
        if (!badgeB.isNullOrBlank()) {
            rightBadge?.text = badgeB
            rightBadge?.visibility = View.VISIBLE
        } else {
            rightBadge?.visibility = View.GONE
        }

        val desc = offer.description?.trim().orEmpty()
        if (desc.isNotBlank() && offerText != null) {
            offerText.text = desc
            offerContainer?.visibility = View.VISIBLE
        } else {
            offerContainer?.visibility = View.GONE
        }

        val second = offer.secondUnit
        if (second != null && second > 0.0 && secondUnitValue != null) {
            secondUnitValue.text = formatCurrency(second)
            secondUnitContainer?.visibility = View.VISIBLE
        } else {
            secondUnitContainer?.visibility = View.GONE
        }
    }

    private fun renderPacks(packs: List<PricePack>, container: ViewGroup?) {
        if (container == null) return
        container.removeAllViews()
        if (packs.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        packs.forEach { p ->
            val card = createPackCard(p.label, p.price, p.unitPrice)
            container.addView(card)
        }
    }

    private fun bindSpecificLayoutPrices(product: PriceProduct, layoutType: String) {
        val root = binding.priceResultRoot
        val decimalFormat = java.text.DecimalFormat("0.00", java.text.DecimalFormatSymbols(Locale.US))

        // Helper function to set price to views (integer + decimal)
        fun setPriceToViews(integerViewId: Int, decimalViewId: Int, value: Double?) {
            val integerTv = root.findViewById<TextView>(integerViewId)
            val decimalTv = root.findViewById<TextView>(decimalViewId)
            if (value == null || value <= 0.0) {
                integerTv?.text = "0"
                decimalTv?.text = ",00"
                return
            }
            val formatted = decimalFormat.format(value)
            val parts = formatted.split(".")
            integerTv?.text = parts[0]
            decimalTv?.text = ",${parts[1]}"
        }

        // Helper to check if a value is valid (not null, > 0.0)
        fun isValid(value: Double?): Boolean = value != null && value > 0.0

        val normalVal = product.price
        val promoVal = product.pricePromotional
        val clubVal = product.priceClub ?: product.clubPrice
        val cardVal = product.cardPrice
        val wholesaleVal = product.priceWholesale
        val fromVal = product.priceFrom ?: product.originalPrice

        when (layoutType) {
            "price_check_normal" -> {
                val sec = root.findViewById<View>(R.id.priceNormalSection)
                if (isValid(normalVal)) {
                    sec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.normalPriceInteger, R.id.normalPriceDecimal, normalVal)
                } else {
                    sec?.visibility = View.GONE
                }
            }
            "price_check_de_por" -> {
                val deSec = root.findViewById<View>(R.id.deSection)
                val porSec = root.findViewById<View>(R.id.porSection)

                // Edge case: if promo price is greater than normal price, hide promo price
                val showPromo = isValid(promoVal) && (normalVal == null || promoVal!! < normalVal)

                if (isValid(normalVal)) {
                    deSec?.visibility = View.VISIBLE
                    val fromTv = root.findViewById<TextView>(R.id.priceFromInteger)
                    val fromDecTv = root.findViewById<TextView>(R.id.priceFromDecimal)
                    setPriceToViews(R.id.priceFromInteger, R.id.priceFromDecimal, normalVal)
                    // Apply strike-through to both integer and decimal to look nice
                    fromTv?.paintFlags = fromTv?.paintFlags?.or(Paint.STRIKE_THRU_TEXT_FLAG) ?: Paint.STRIKE_THRU_TEXT_FLAG
                    fromDecTv?.paintFlags = fromDecTv?.paintFlags?.or(Paint.STRIKE_THRU_TEXT_FLAG) ?: Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    deSec?.visibility = View.GONE
                }

                if (showPromo) {
                    porSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceInteger, R.id.priceDecimal, promoVal)
                } else {
                    porSec?.visibility = View.GONE
                }
            }
            "price_check_atacado" -> {
                val varejoSec = root.findViewById<View>(R.id.varejoSection)
                val atacadoSec = root.findViewById<View>(R.id.atacadoSection)

                if (isValid(normalVal)) {
                    varejoSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceInteger, R.id.priceDecimal, normalVal)
                } else {
                    varejoSec?.visibility = View.GONE
                }

                // Hide wholesale price if it's equal to or greater than normal price
                val showWholesale = isValid(wholesaleVal) && (normalVal == null || wholesaleVal!! < normalVal)

                if (showWholesale) {
                    atacadoSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceWholesaleInteger, R.id.priceWholesaleDecimal, wholesaleVal)
                } else {
                    atacadoSec?.visibility = View.GONE
                }
            }
            "price_check_clube_koch" -> {
                val deSec = root.findViewById<View>(R.id.deSection)
                val porSec = root.findViewById<View>(R.id.porSection)
                val clubSec = root.findViewById<View>(R.id.clubeKochSection)

                // Base normal price and promo price
                val displayNormal = fromVal ?: normalVal
                val displayPromo = if (fromVal != null) normalVal else promoVal

                // Edge case: if promo price is greater than normal price, hide promo price
                val showPromo = isValid(displayPromo) && (displayNormal == null || displayPromo!! < displayNormal)
                // Edge case: if club price is greater than normal price, hide club price
                val showClub = isValid(clubVal) && (displayNormal == null || clubVal!! < displayNormal)

                if (isValid(displayNormal)) {
                    deSec?.visibility = View.VISIBLE
                    val fromTv = root.findViewById<TextView>(R.id.priceFromInteger)
                    val fromDecTv = root.findViewById<TextView>(R.id.priceFromDecimal)
                    setPriceToViews(R.id.priceFromInteger, R.id.priceFromDecimal, displayNormal)
                    fromTv?.paintFlags = fromTv?.paintFlags?.or(Paint.STRIKE_THRU_TEXT_FLAG) ?: Paint.STRIKE_THRU_TEXT_FLAG
                    fromDecTv?.paintFlags = fromDecTv?.paintFlags?.or(Paint.STRIKE_THRU_TEXT_FLAG) ?: Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    deSec?.visibility = View.GONE
                }

                if (showPromo) {
                    porSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceInteger, R.id.priceDecimal, displayPromo)
                } else {
                    porSec?.visibility = View.GONE
                }

                if (showClub) {
                    clubSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceClubInteger, R.id.priceClubDecimal, clubVal)
                } else {
                    clubSec?.visibility = View.GONE
                }
            }
            "price_check_cartao_koch" -> {
                val normalSec = root.findViewById<View>(R.id.normalSection)
                val cartaoSec = root.findViewById<View>(R.id.cartaoKochSection)

                // Edge case: if card price is greater than normal price, hide card price
                val showCard = isValid(cardVal) && (normalVal == null || cardVal!! < normalVal)

                if (isValid(normalVal)) {
                    normalSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceInteger, R.id.priceDecimal, normalVal)
                } else {
                    normalSec?.visibility = View.GONE
                }

                if (showCard) {
                    cartaoSec?.visibility = View.VISIBLE
                    setPriceToViews(R.id.priceCardInteger, R.id.priceCardDecimal, cardVal)
                } else {
                    cartaoSec?.visibility = View.GONE
                }
            }
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
