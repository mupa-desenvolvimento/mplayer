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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Bundle
import android.os.Process
import android.speech.tts.UtteranceProgressListener
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
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
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
import com.mupa.player.enterprise.player.MediaPlayLogsSyncManager
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.databinding.ActivityPlayerBinding
import com.mupa.player.enterprise.demo.DemoColors
import com.mupa.player.enterprise.demo.DemoImageCache
import com.mupa.player.enterprise.demo.DemoProduct
import com.mupa.player.enterprise.demo.DemoProductRepository
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestFetchResult
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.MediaSyncProgress
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.player.PlaybackProfile
import com.mupa.player.enterprise.player.PlayerEngine
import com.mupa.player.enterprise.player.TransitionConfig
import com.mupa.player.enterprise.price.PriceConfig
import com.mupa.player.enterprise.price.PriceConfigParser
import com.mupa.player.enterprise.price.PriceConfigXmlImporter
import com.mupa.player.enterprise.price.PriceAnalyticsSyncManager
import com.mupa.player.enterprise.price.MissingProductImageSyncManager
import com.mupa.player.enterprise.price.PriceOffer
import com.mupa.player.enterprise.price.PriceProduct
import com.mupa.player.enterprise.price.PricePack
import com.mupa.player.enterprise.price.PriceTheme
import com.mupa.player.enterprise.price.PriceQueryEngine
import com.mupa.player.enterprise.price.LayoutConfig
import com.mupa.player.enterprise.price.PriceSlot
import com.mupa.player.enterprise.price.PriceStep
import com.mupa.player.enterprise.price.ProductPriceSlot
import com.mupa.player.enterprise.security.TotpPasswordGenerator
import com.mupa.player.enterprise.monitoring.DeviceEventSyncManager
import com.mupa.player.enterprise.monitoring.MediaDownloadFailureSyncManager
import com.mupa.player.enterprise.price.AdvantageType
import com.mupa.player.enterprise.price.OfferIntelligence
import com.mupa.player.enterprise.price.PriceVoiceSynth
import com.mupa.player.enterprise.price.SmartDescription
import androidx.core.widget.TextViewCompat
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.Guideline
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.doOnLayout
import android.text.style.RelativeSizeSpan
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** Tag do badge de preço sobreposto na arte publicitária (ver [updatePriceBadge]). */
    private val PRICE_ART_BADGE_TAG = "price_art_badge"

    /** Tag das caixas de conversão de moeda (Dólar/Peso) sobrepostas na arte (ver [updatePriceBadge]). */
    private val PRICE_ART_CURRENCY_TAG = "price_art_currency"

    /** Tag da foto do produto na Consulta Patrocinada (ver [updatePriceBadge]) — view separada do
     * [PRICE_ART_BADGE_TAG] (nome/preço) de propósito: fica atrás da arte do patrocínio (pedido
     * do usuário, 2026-11-26), então a arte (com seu recorte transparente) cobre o que passar da
     * área reservada em vez de aparecer por cima dela. */
    private val PRICE_ART_SPONSORED_PHOTO_TAG = "price_art_sponsored_photo"

    /** Telas Patrocinadas: última região reservada conhecida (ver [PriceQueryEngine.SponsorRegion]).
     * O render INICIAL de cada scan usa o produto já em cache (sem chamar
     * preloadProductImageAndTheme de novo), então não tem a região fresca na hora — fica com a
     * última vista nesta sessão até o fetch em segundo plano (sempre disparado pelo menos 1x por
     * janela de 60 min) confirmar/atualizar. Nula = sem campanha patrocinada ativa. */
    private var lastSponsorRegion: PriceQueryEngine.SponsorRegion? = null

    /** Mesma lógica "sticky" de [lastSponsorRegion], pro modo de composição (foto+nome+preço)
     * da Consulta Patrocinada — ver updatePriceBadge. */
    private var lastIsSponsoredLookup: Boolean = false
    private var lastSponsorProductPhotoPath: String? = null
    private lateinit var deviceId: String
    private lateinit var playerEngine: PlayerEngine
    private val barcodeSimulationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ean = intent.getStringExtra("ean") ?: return
            onBarcodeCaptured(ean)
        }
    }

    // Scanner Meferi (MC45 etc.) em modo intent output: o MeWedge envia o código
    // decodificado via broadcast MEF_ACTION em vez de digitar no input focado
    private val meferiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras ?: return
            val barcode = extras.keySet()
                .asSequence()
                .mapNotNull { key -> extras.getString(key) ?: (extras.get(key) as? ByteArray)?.let { String(it) } }
                .map { it.trim() }
                .firstOrNull { it.length in 4..20 && it.all { ch -> ch.isDigit() } }
            if (barcode != null) {
                Log.i("MPlayerScan", "meferi_intent_scan barcode=$barcode")
                onBarcodeCaptured(barcode)
            } else {
                Log.w("MPlayerScan", "meferi_intent_scan_no_barcode extras=${extras.keySet().joinToString()}")
            }
        }
    }

    private lateinit var manifestManager: ManifestManager
    // Porte de mPlayer_x96 (docs/PLANO_PORTE_MANIFEST_EVENTS_LISTENER.md, 2026-10-19):
    // tenant atual (do manifesto, não das Configurações) pra filtrar eventos broadcast
    // do canal manifest-events sem serials explícitos — ver syncTenantFromManifest.
    private var currentTenantId: String? = null
    private var manifestEventsListener: com.mupa.player.enterprise.network.ManifestEventsListener? = null
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

    // Monitoramento proativo (heartbeat inteligente + falha repetida + retomada de conectividade).
    // Ver MUPA_PLATFORM_DEVICE_EVENTS_CONTRACT.md.
    private var consecutiveFailEan: String = ""
    private var consecutiveFailCount: Int = 0
    private var consecutiveFailReported: Boolean = false
    private var offlineSinceEpochMs: Long? = null
    private var lastMeaningfulEventEpochMs: Long = System.currentTimeMillis()
    private var lastHeartbeatSentEpochMs: Long = System.currentTimeMillis()
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
    // Voz Azure com cache compartilhado (ver PriceVoiceSynth) — substitui o
    // TextToSpeech nativo acima nos 3 pontos que falam preço/produto; o
    // nativo continua de pé só como fallback (sem rede, cache frio e a
    // Edge Function fora do ar, por exemplo) pra nunca deixar a loja muda.
    private val priceVoiceSynth by lazy { PriceVoiceSynth(applicationContext) }
    private var tone: ToneGenerator? = null
    private var lastSpokenEan: String = ""
    private var lastSpokenAtMs: Long = 0L
    private var lastNotFoundEan: String = ""
    private var lastNotFoundAtMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    // Timer único de retorno às mídias. A "geração" garante que só o timer do produto
    // atualmente exibido possa fechar o overlay (um scan novo invalida o timer anterior).
    private var overlayGeneration = 0
    private var returnToMediaRunnable: Runnable? = null
    // Mantido por compatibilidade com onDestroy/onPause; delega ao mecanismo unificado
    private val ttsAutoDismissRunnable = Runnable { setPriceOverlayVisible(false) }

    /**
     * Agenda o retorno às mídias. Cancela qualquer timer anterior (fallback ou pós-TTS) e
     * agenda um único fechamento. [reason] só para log.
     */
    private fun scheduleReturnToMedia(delayMs: Long, reason: String) {
        cancelReturnToMedia()
        val gen = overlayGeneration
        Log.i("MPlayerScan", "schedule_return delay=${delayMs}ms reason=$reason gen=$gen")
        val r = object : Runnable {
            override fun run() {
                // Só fecha se ainda for o mesmo produto exibido (um scan mais novo invalida)
                if (gen != overlayGeneration) return
                // Nunca volta para a mídia com o TTS ainda falando — espera terminar.
                if (tts?.isSpeaking == true) {
                    returnToMediaRunnable = this
                    mainHandler.postDelayed(this, 400L)
                    return
                }
                setPriceOverlayVisible(false)
            }
        }
        returnToMediaRunnable = r
        mainHandler.postDelayed(r, delayMs.coerceAtLeast(500L))
    }

    private fun cancelReturnToMedia() {
        returnToMediaRunnable?.let { mainHandler.removeCallbacks(it) }
        returnToMediaRunnable = null
        mainHandler.removeCallbacks(ttsAutoDismissRunnable)
        hideOverlayJob?.cancel()
    }

    private val demoHttp: OkHttpClient by lazy { TlsCompat.newClient() }
    // #region debug-point A:orientation-debug-reporter
    private fun debugOrientationEvent(
        hypothesisId: String,
        location: String,
        msg: String,
        data: JSONObject = JSONObject(),
    ) {
        val body =
            JSONObject()
                .put("sessionId", "device-orientation-layout")
                .put("runId", "pre-fix")
                .put("hypothesisId", hypothesisId)
                .put("location", location)
                .put("msg", "[DEBUG] $msg")
                .put("data", data)
                .put("ts", System.currentTimeMillis())
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                demoHttp.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:7777/event")
                        .post(body)
                        .build(),
                ).execute().use { }
            }
        }
    }
    // #endregion
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
        BrandTypography.applyBrandTypography(binding.priceResultRoot, this)
        binding.apkVersionWatermark.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        demoRepo = DemoProductRepository(applicationContext)
        demoImageCache = DemoImageCache(applicationContext)
        setupDevModeToggle()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(barcodeSimulationReceiver, IntentFilter("com.mupa.player.enterprise.SIMULATE_BARCODE"), RECEIVER_EXPORTED)
            registerReceiver(meferiScanReceiver, IntentFilter("android.intent.action.MEF_ACTION"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeSimulationReceiver, IntentFilter("com.mupa.player.enterprise.SIMULATE_BARCODE"))
            registerReceiver(meferiScanReceiver, IntentFilter("android.intent.action.MEF_ACTION"))
        }

        // Leitor integrado Gertec (SK100 etc.): ativado no onResume quando habilitado nas Configurações

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
            val hadTcServer = tcServerAddress.isNotBlank()
            refreshTcServerAddress()
            if (shouldForceTcServerIntegration()) {
                ensureTcServerDefaultPriceConfigIfNeeded()
            } else if (hadTcServer) {
                // endereço foi removido nas configurações: volta para a config do manifesto
                priceConfig = null
            }
            val hadUnidasulServer = unidasulServerAddress.isNotBlank()
            refreshUnidasulServerAddress()
            if (shouldForceUnidasulIntegration()) {
                ensureUnidasulDefaultPriceConfigIfNeeded()
            } else if (hadUnidasulServer) {
                // endereço foi removido nas configurações: volta para a config do manifesto
                priceConfig = null
            }
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
        manifestEventsListener?.stop()
        manifestEventsListener = null
        runCatching { unregisterReceiver(barcodeSimulationReceiver) }
        runCatching { unregisterReceiver(meferiScanReceiver) }
        priceAnimator?.cancel()
        mainHandler.removeCallbacks(ttsAutoDismissRunnable)
        tts?.stop()
        tts?.shutdown()
        tts = null
        priceVoiceSynth.stop()
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPriceOverlayLayout()
    }

    private suspend fun startLoop() {
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()
        if (deviceId.isBlank()) {
            deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
        }

        // Porte de mPlayer_x96 (docs/PLANO_PORTE_MANIFEST_EVENTS_LISTENER.md, 2026-10-19):
        // escuta o canal Supabase Realtime manifest-events pra sincronizar na hora que o
        // painel publica, em vez de esperar o polling horário mais abaixo neste método
        // (que continua existindo como rede de segurança). currentTenantId começa com o
        // valor em cache; syncTenantFromManifest mantém em dia a cada fetch bem-sucedido.
        val cacheForTenant = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        currentTenantId = cacheForTenant?.tenant?.trim()?.ifBlank { null }
        manifestEventsListener?.stop()
        manifestEventsListener = com.mupa.player.enterprise.network.ManifestEventsListener(
            getDeviceId = { deviceId },
            getTenantId = { currentTenantId },
            onReloadRequested = { reason ->
                Log.i("PlayerActivity", "Recarga de manifesto solicitada via manifest-events ($reason)")
                lifecycleScope.launch {
                    // Atalho pragmático (2026-10-19): enquanto device-api-v2 não devolver
                    // is_inoperative no manifesto (confirmado ao vivo que ainda não devolve —
                    // ver docs/portabilidade_bloqueio_tenant_main.md), reage direto ao texto
                    // do evento realtime pros dois motivos que o backend já manda hoje.
                    //
                    // Achado real em teste ao vivo (2026-10-20, G-BOT): chamar
                    // refreshInBackground() logo depois de mostrar o bloqueio desfazia o
                    // bloqueio sozinho — o manifesto buscado não tem is_inoperative (mesma
                    // lacuna), então refreshInBackground() caía no ramo "else" e chamava
                    // hideInoperativeScreen() de novo, ~1s depois, com o player já parado
                    // (playerEngine.stop() do showInoperativeScreen) e nada reiniciando a
                    // reprodução — tela preta. Fix: em "tenant_blocked", mostra a tela e
                    // PÁRA aqui — não busca manifesto nenhum, que só ia contradizer o que
                    // acabamos de aplicar. Em "tenant_unblocked" o refreshInBackground()
                    // continua necessário: é quem traz o conteúdo de verdade de volta e
                    // reinicia a reprodução.
                    if (reason == "tenant_blocked") {
                        runCatching { showInoperativeScreen(reason) }
                        return@launch
                    }
                    if (reason == "tenant_unblocked") {
                        runCatching { hideInoperativeScreen() }
                    }
                    try {
                        refreshInBackground()
                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "Erro no refreshInBackground via manifest-events", e)
                    }
                }
            }
        ).also { it.start() }

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
        ensureKompraoDefaultPriceConfigIfNeeded()
        lifecycleScope.launch {
            refreshTcServerAddress()
            ensureTcServerDefaultPriceConfigIfNeeded()
        }
        lifecycleScope.launch {
            refreshUnidasulServerAddress()
            ensureUnidasulDefaultPriceConfigIfNeeded()
        }
        lifecycleScope.launch {
            refreshCustomQueryXml()
            ensureCustomQueryDefaultPriceConfigIfNeeded()
        }

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
                    runCatching { MediaPlayLogsSyncManager(applicationContext).uploadPending() }
                    runCatching { MissingProductImageSyncManager(applicationContext).uploadPending() }
                    runCatching { DeviceEventSyncManager(applicationContext).uploadPending() }
                    runCatching { MediaDownloadFailureSyncManager(applicationContext).uploadPending() }
                }
            }
        }

        // Monitoramento proativo (heartbeat inteligente, nunca fixo): tick leve a cada 1 minuto
        // só pra decidir SE deve mandar algo — nunca manda no tick em si. Cobre: retomada de
        // conectividade (com a duração da queda) e ociosidade prolongada (sem nenhum scan).
        // Ver MUPA_PLATFORM_DEVICE_EVENTS_CONTRACT.md.
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60 * 1000L)
                val online = isOnline()
                val now = System.currentTimeMillis()

                if (online) {
                    val since = offlineSinceEpochMs
                    if (since != null) {
                        offlineSinceEpochMs = null
                        val offlineSeconds = ((now - since) / 1000L).toInt()
                        reportDeviceEvent(eventType = "connectivity_restored", offlineDurationSeconds = offlineSeconds)
                        reportDeviceEvent(eventType = "heartbeat", reason = "state_change")
                        lastHeartbeatSentEpochMs = now
                    }
                } else if (offlineSinceEpochMs == null) {
                    offlineSinceEpochMs = now
                }

                val idleMinutes = runCatching { SettingsManager(applicationContext).getHeartbeatIdleMinutes() }
                    .getOrDefault(SettingsManager.DEFAULT_HEARTBEAT_IDLE_MINUTES)
                val idleMillis = idleMinutes * 60_000L
                val quietSinceEpochMs = maxOf(lastMeaningfulEventEpochMs, lastHeartbeatSentEpochMs)
                if (online && now - quietSinceEpochMs >= idleMillis) {
                    reportDeviceEvent(eventType = "heartbeat", reason = "idle_timeout")
                    lastHeartbeatSentEpochMs = now
                }
            }
        }

        // Verifica, sem nunca atrasar uma consulta de preço, se alguma imagem antes ausente já
        // foi cadastrada manualmente por um técnico — intervalo generoso pois a resolução é manual.
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(4 * 60 * 60 * 1000L) // every 4 hours
                if (isOnline()) {
                    runCatching { priceEngine?.checkAndRecoverMissingImages() }
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

    // Porte de mPlayer_x96 (commits c3de040/fadc1dc) + docs/portabilidade_bloqueio_tenant_main.md
    // (2026-10-19): bloqueio nativo de dispositivo inoperante, mesmo recurso que já existe na
    // X96. Sem motivo visível na tela (removido no fix original) — só o aviso genérico + ID,
    // pra suporte. Na `main` (diferente da x96) isso também precisa isolar os recursos que só
    // existem aqui: scanner/consulta de preço (dismissPriceDialogIfOpen) e audiência facial
    // via câmera (pauseAudienceTracking) — ver doc §2.
    private suspend fun showInoperativeScreen(reason: String?) {
        pauseAudienceTracking()
        dismissPriceDialogIfOpen()
        withContext(Dispatchers.Main) {
            playerEngine.stop()
            setSyncOverlayVisible(false)
            binding.inoperativeOverlay.visibility = View.VISIBLE
            val devName = binding.deviceNameText.text.toString().takeIf { it.isNotBlank() } ?: deviceId
            binding.inoperativeDeviceDetails.text = "Dispositivo: $devName • ID: $deviceId"
        }
        Log.w("PlayerActivity", "Dispositivo inoperante ativo. Motivo interno: ${reason ?: "não especificado"}")
    }

    private suspend fun hideInoperativeScreen() {
        val wasVisible = withContext(Dispatchers.Main) {
            val visible = binding.inoperativeOverlay.visibility != View.GONE
            if (visible) binding.inoperativeOverlay.visibility = View.GONE
            visible
        }
        if (wasVisible) {
            resumeAudienceTracking()
            Log.i("PlayerActivity", "Dispositivo reativado. Ocultando tela de bloqueio.")
        }
    }

    // Equivalente ao stop() já usado em ensureAudienceStarted() quando a licença/hardware não
    // permite mais rodar — aqui é o mesmo desligamento, só que disparado pelo bloqueio, não por
    // mudança de licença. audienceStarted=false faz ensureAudienceStarted() (chamado por
    // resumeAudienceTracking) reconstruir tudo do zero quando desbloquear, sem estado zumbi.
    private suspend fun pauseAudienceTracking() {
        if (audienceStarted) {
            runCatching { audienceManager?.stop() }
            audienceManager = null
            audienceStarted = false
            withContext(Dispatchers.Main) { binding.txtTransparencyWarning.visibility = View.GONE }
        }
    }

    // ensureAudienceStarted() já é idempotente e confere licença/hardware/permissão sozinha —
    // só reaproveita a mesma função que starLoop() chama no boot normal.
    private suspend fun resumeAudienceTracking() {
        runCatching { ensureAudienceStarted() }
    }

    // Fecha qualquer consulta de preço em tela (overlay + TTS falando) antes do bloqueio tomar
    // conta — sem isso, o overlay de preço (menor elevation) continuaria visível por baixo do
    // aviso de inoperante, e o TTS podia ficar falando sobre uma tela já bloqueada.
    private suspend fun dismissPriceDialogIfOpen() {
        withContext(Dispatchers.Main) {
            cancelReturnToMedia()
            runCatching { tts?.stop() }
            priceVoiceSynth.stop()
            setPriceOverlayVisible(false)
        }
    }

    private suspend fun tryStartOfflinePlayback(): Boolean {
        val offlineJson = manifestManager.loadOfflineManifest(deviceId).orEmpty().trim()
        if (offlineJson.isBlank()) return false
        val inopOffline = manifestManager.parseInoperativeInfo(offlineJson)
        if (inopOffline.isInoperative) {
            showInoperativeScreen(inopOffline.reason)
            return true
        }
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

    // Porte de mPlayer_x96 (docs/PLANO_PORTE_MANIFEST_EVENTS_LISTENER.md, 2026-10-19):
    // mantém currentTenantId (e o DeviceCache) em dia a cada manifesto buscado com
    // sucesso — é o filtro que o ManifestEventsListener usa pra aceitar/ignorar um
    // evento broadcast do canal manifest-events sem lista de serials explícita.
    private suspend fun syncTenantFromManifest(manifestJson: String) {
        val parsedTenant = manifestManager.parseTenantId(manifestJson)
        if (!parsedTenant.isNullOrBlank() && parsedTenant != currentTenantId) {
            Log.i("PlayerActivity", "Tenant atualizado a partir do manifesto: $parsedTenant")
            currentTenantId = parsedTenant
            runCatching {
                val cm = DeviceCacheManager(applicationContext)
                val c = cm.load()
                if (c != null) cm.save(c.copy(tenant = parsedTenant))
            }
        }
    }

    // Backoff exponencial com jitter pro retry do manifesto — evita que uma falha determinística
    // (404/400/401, nunca vai se resolver sozinha) ou uma falha generalizada de backend vire um
    // "thundering herd" contra a Edge Function (caso real: um único SK-210 preso num 404 disparou
    // 17.499 chamadas num dia inteiro com o delay fixo de 3s antigo). Dobra a cada tentativa a
    // partir de 3s, teto de 300s, jitter de ±20% pra não sincronizar os retries da frota inteira.
    private fun manifestBackoffDelayMs(attempt: Int): Long {
        val base = 3_000L
        val doubled = base * (1L shl attempt.coerceIn(0, 10))
        val capped = doubled.coerceAtMost(300_000L)
        val jitterRange = (capped / 5).coerceAtLeast(1L)
        val jitter = (-jitterRange..jitterRange).random()
        return (capped + jitter).coerceAtLeast(1_000L)
    }

    private fun describeManifestFetchError(result: ManifestFetchResult): String = when (result) {
        is ManifestFetchResult.HttpError -> "HTTP ${result.code}: ${result.message}"
        is ManifestFetchResult.NetworkError -> "Erro de rede: ${result.message}"
        is ManifestFetchResult.Success -> ""
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

        var fetchResult = manifestManager.fetchManifest(deviceId)
        var backoffAttempt = 0
        while (fetchResult !is ManifestFetchResult.Success || fetchResult.json.isBlank()) {
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
            val delayMs = manifestBackoffDelayMs(backoffAttempt)
            backoffAttempt++
            Log.w("MPlayerSync", "fetch_manifest_failed deviceId=$deviceId result=$fetchResult nextRetryMs=$delayMs")
            updateSyncTexts(
                status = "Não foi possível obter a programação. Tentando novamente...",
                countText = "",
                fileText = "",
                detailText = "${describeManifestFetchError(fetchResult)} · nova tentativa em ${delayMs / 1000}s",
                progressPercent = null,
            )
            delay(delayMs)
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
                backoffAttempt = 0 // rede voltou: o problema pode ter sido só a queda, recomeça do delay mínimo
            }
            fetchResult = manifestManager.fetchManifest(deviceId)
        }
        val remote = fetchResult.json.trim()

        syncTenantFromManifest(remote)

        val inop = manifestManager.parseInoperativeInfo(remote)
        if (inop.isInoperative) {
            manifestManager.saveManifest(deviceId, remote)
            showInoperativeScreen(inop.reason)
            return
        }
        hideInoperativeScreen()

        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (changed) {
            manifestManager.saveManifest(deviceId, remote)
        }

        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyPriceConfigFromManifestJson(remote)
        applyTransitionConfigFromManifestJson(remote)
        priceEngine?.updateSponsoredLookups(manifestManager.parseSponsoredLookups(remote))
        lifecycleScope.launch { runCatching { priceEngine?.precacheSponsoredLookups() } }

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
        var attempts = 1
        val maxAttempts = 3
        while (playlist.size < items.size && attempts < maxAttempts) {
            attempts++
            // Se já tivermos ao menos uma mídia baixada, inicia a reprodução imediatamente
            if (playlist.isNotEmpty() && playerEngine.getCurrentItemId() == null) {
                playerEngine.start(playlist)
                setSyncOverlayVisible(false)
            }

            val isPlaying = playerEngine.getCurrentItemId() != null
            if (!isPlaying) {
                val missing = (items.size - playlist.size).coerceAtLeast(0)
                updateSyncTexts(
                    status = "Baixando conteúdos...",
                    countText = "Faltando $missing de ${items.size} mídias",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
            } else {
                setSyncPillVisible(true, "Atualizando conteúdos...")
            }

            delay(2500L)
            runCatching {
                manifestManager.syncMedia(
                    deviceId = deviceId,
                    manifestJson = remote,
                    onProgress = { p ->
                        runOnUiThread {
                            if (playerEngine.getCurrentItemId() == null) {
                                renderProgress(p)
                            } else {
                                val pct = if (p.totalItems > 0) (p.completedItems * 100) / p.totalItems else null
                                val txt = if (pct != null) "Atualizando conteúdos... $pct%" else "Atualizando conteúdos..."
                                setSyncPillVisible(true, txt, pct)
                            }
                        }
                    },
                    maxConcurrentDownloads = 1,
                )
            }
            playlist = buildLocalPlaylist(items)
        }

        setSyncPillVisible(false)

        if (playlist.isNotEmpty()) {
            playerEngine.setPlaylist(playlist)
            if (playerEngine.getCurrentItemId() == null) {
                playerEngine.start(playlist)
            }
            setSyncOverlayVisible(false)
        } else {
            // Caso extremo: nenhuma mídia pôde ser baixada do novo manifest
            // 1. Tenta fallback para mídias anteriores gravadas no armazenamento local persistente
            val fallbackStarted = tryStartOfflinePlayback()
            if (!fallbackStarted) {
                // 2. Armazenamento local vazio: exibe tela de contagem regressiva de 30s
                while (playlist.isEmpty()) {
                    setSyncOverlayVisible(true)
                    binding.syncLogo.visibility = View.VISIBLE
                    binding.syncProgressBar.visibility = View.INVISIBLE
                    binding.syncSpinner.visibility = View.VISIBLE
                    binding.syncCountText.visibility = View.GONE
                    binding.syncFileText.visibility = View.GONE
                    binding.syncDetailText.visibility = View.GONE

                    for (sec in 30 downTo 1) {
                        updateSyncTexts(
                            status = "Não foi possível encontrar seus conteúdos, tentando novamente em $sec segundos",
                            countText = "",
                            fileText = "",
                            detailText = "",
                            progressPercent = null,
                        )
                        delay(1000L)
                    }

                    updateSyncTexts(
                        status = "Tentando obter conteúdos...",
                        countText = "",
                        fileText = "",
                        detailText = "",
                        progressPercent = null,
                    )
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
                playerEngine.start(playlist)
                setSyncOverlayVisible(false)
            }
        }
    }

    private suspend fun refreshInBackground(): Boolean {
        if (!isOnline()) return false
        val validation = runCatching {
            com.mupa.player.enterprise.services.DeviceValidationService(applicationContext).validateDevice(deviceId)
        }.getOrNull()
        if (validation is com.mupa.player.enterprise.services.DeviceValidationResult.Found && validation.heartbeatRequested) {
            reportDeviceEvent(eventType = "heartbeat", reason = "requested")
            lastHeartbeatSentEpochMs = System.currentTimeMillis()
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

        val fetchResult = manifestManager.fetchManifest(deviceId)
        if (fetchResult !is ManifestFetchResult.Success || fetchResult.json.isBlank()) {
            Log.w("MPlayerSync", "refresh_in_background_failed deviceId=$deviceId result=$fetchResult")
            return false
        }
        val remote = fetchResult.json.trim()
        syncTenantFromManifest(remote)

        val inop = manifestManager.parseInoperativeInfo(remote)
        if (inop.isInoperative) {
            manifestManager.saveManifest(deviceId, remote)
            showInoperativeScreen(inop.reason)
            return true
        }
        hideInoperativeScreen()

        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (!changed) return true

        manifestManager.saveManifest(deviceId, remote)
        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyPriceConfigFromManifestJson(remote)
        applyTransitionConfigFromManifestJson(remote)
        priceEngine?.updateSponsoredLookups(manifestManager.parseSponsoredLookups(remote))
        lifecycleScope.launch { runCatching { priceEngine?.precacheSponsoredLookups() } }
        // Sincronização pós-inicialização: discreta via Pill, sem overlay invasivo na tela
        val isPlaying = playerEngine.getCurrentItemId() != null
        if (!isPlaying) {
            setSyncOverlayVisible(true)
            updateSyncTexts(
                status = "Sincronizando conteúdos...",
                countText = "",
                fileText = "",
                detailText = "",
                progressPercent = null,
            )
        } else {
            setSyncPillVisible(true, "Atualizando conteúdos...")
        }

        val items = manifestManager.parseItemsPublic(remote)
        var playlist = buildLocalPlaylist(items)
        if (playlist.size == items.size) {
            playerEngine.setPlaylist(playlist)
            setSyncOverlayVisible(false)
            setSyncPillVisible(false)
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
                onProgress = { p ->
                    runOnUiThread {
                        if (playerEngine.getCurrentItemId() == null) {
                            renderProgress(p)
                        } else {
                            val pct = if (p.totalItems > 0) (p.completedItems * 100) / p.totalItems else null
                            val txt = if (pct != null) "Atualizando conteúdos... $pct%" else "Atualizando conteúdos..."
                            setSyncPillVisible(true, txt, pct)
                        }
                    }
                },
                maxConcurrentDownloads = 1,
            )
        }

        playlist = buildLocalPlaylist(items)
        var attempts = 0
        while (playlist.size < items.size && attempts < 3) {
            attempts++
            delay(2500L)
            runCatching {
                manifestManager.syncMedia(
                    deviceId = deviceId,
                    manifestJson = remote,
                    onProgress = { p ->
                        runOnUiThread {
                            if (playerEngine.getCurrentItemId() == null) {
                                renderProgress(p)
                            } else {
                                val pct = if (p.totalItems > 0) (p.completedItems * 100) / p.totalItems else null
                                val txt = if (pct != null) "Atualizando conteúdos... $pct%" else "Atualizando conteúdos..."
                                setSyncPillVisible(true, txt, pct)
                            }
                        }
                    },
                    maxConcurrentDownloads = 1,
                )
            }
            playlist = buildLocalPlaylist(items)
        }

        setSyncPillVisible(false)
        if (playlist.isNotEmpty()) {
            playerEngine.setPlaylist(playlist)
        }
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
        if (binding.inoperativeOverlay.visibility == View.VISIBLE) return
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

    private var syncPillHideJob: Job? = null

    private fun setSyncPillVisible(visible: Boolean, text: String? = null, progressPercent: Int? = null) {
        syncPillHideJob?.cancel()
        binding.syncPillContainer.animate().cancel()

        if (visible) {
            if (text != null) {
                binding.syncPillText.text = text
            }
            if (progressPercent != null && progressPercent in 0..100) {
                binding.syncPillProgressBar.isIndeterminate = false
                binding.syncPillProgressBar.progress = progressPercent
            } else {
                binding.syncPillProgressBar.isIndeterminate = true
            }

            if (binding.syncPillContainer.visibility != View.VISIBLE) {
                binding.syncPillContainer.visibility = View.VISIBLE
                binding.syncPillContainer.alpha = 0f
                binding.syncPillContainer.animate().alpha(1f).setDuration(200).start()
            }
        } else {
            syncPillHideJob = lifecycleScope.launch {
                delay(800L)
                binding.syncPillContainer.animate().alpha(0f).setDuration(250).withEndAction {
                    binding.syncPillContainer.visibility = View.GONE
                }.start()
            }
        }
    }

    /**
     * Pedido do usuário (2026-11-23): nunca mais bloquear a tela inteira (nem o card no rodapé)
     * pra avisar que está carregando/sincronizando conteúdo novo — um único indicador discreto
     * no canto superior direito (o mesmo [syncPillContainer] que já existia pro sync em segundo
     * plano) substitui os dois comportamentos antigos. `syncOverlay`/`syncContent` continuam no
     * layout (há outras views dentro dele, como `deviceNameText`, lidas por
     * [showInoperativeScreen]) mas nunca mais ficam visíveis — fica tudo delegado pro pill.
     */
    private fun setSyncOverlayVisible(visible: Boolean) {
        setSyncPillVisible(visible)
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

    /**
     * Grava localmente um evento de monitoramento (heartbeat, falha repetida do mesmo item ou
     * retomada de conectividade) e, se online, tenta subir pro Supabase imediatamente (melhor
     * esforço, sem bloquear quem chamou) — senão fica na fila pro próximo ciclo horário de
     * `DeviceEventSyncManager`, junto com as outras sincronizações. Ver
     * `MUPA_PLATFORM_DEVICE_EVENTS_CONTRACT.md`.
     */
    private fun reportDeviceEvent(
        eventType: String,
        reason: String? = null,
        ean: String? = null,
        failCount: Int? = null,
        offlineDurationSeconds: Int? = null,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
            val entity = com.mupa.player.enterprise.storage.db.DeviceEventEntity(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                companyId = cache?.company?.trim()?.ifBlank { null },
                filial = cache?.filial?.trim()?.ifBlank { null },
                eventType = eventType,
                reason = reason,
                ean = ean,
                failCount = failCount,
                offlineDurationSeconds = offlineDurationSeconds,
                createdAtEpochMs = System.currentTimeMillis(),
                uploadedAtEpochMs = null,
            )
            runCatching { AppDatabase.get(applicationContext).deviceEventDao().insert(entity) }
            if (isOnline()) {
                runCatching { DeviceEventSyncManager(applicationContext).uploadPending() }
            }
        }
    }

    private fun onBarcodeCaptured(raw: String) {
        // docs/portabilidade_bloqueio_tenant_main.md §3.E.6: dispositivo bloqueado não abre
        // consulta de preço — o leitor pode continuar lendo (hardware), mas a UI nunca reage.
        if (binding.inoperativeOverlay.visibility == View.VISIBLE) {
            Log.d("PlayerActivity", "Código de barras ignorado: dispositivo bloqueado.")
            return
        }
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

            // Código de barras específico por aparelho: "settings_{últimos 4 dígitos do device_id}".
            // O próprio código já é o segredo (distribuído fisicamente por dispositivo), então
            // abre direto, sem diálogo adicional.
            if (deviceId.length >= 4 && ean.equals("settings_${deviceId.takeLast(4)}", ignoreCase = true)) {
                startActivity(Intent(this@PlayerActivity, SettingsActivity::class.java))
                return@launch
            }

            val cmd = ean.trim().lowercase(Locale.US)
            if (cmd == "devon" || cmd == "devmode=1" || cmd == "devmode=true" || cmd == "demoon") {
                runCatching { SettingsManager(applicationContext).setDevMode(true) }
                devMode = true
                updateDeviceWatermark()
                reportDeviceEvent(eventType = "heartbeat", reason = "state_change")
                Toast.makeText(this@PlayerActivity, "DEMO ativado", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (cmd == "devoff" || cmd == "devmode=0" || cmd == "devmode=false" || cmd == "demooff") {
                runCatching { SettingsManager(applicationContext).setDevMode(false) }
                devMode = false
                updateDeviceWatermark()
                reportDeviceEvent(eventType = "heartbeat", reason = "state_change")
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
                    if (shouldForceTcServerIntegration()) {
                        ensureTcServerDefaultPriceConfigIfNeeded()
                    } else if (shouldForceCustomQueryIntegration()) {
                        ensureCustomQueryDefaultPriceConfigIfNeeded()
                    } else if (shouldForceAssaiIntegration()) {
                        ensureAssaiDefaultPriceConfigIfNeeded()
                    } else if (shouldForceAmericanasIntegration()) {
                        ensureAmericanasDefaultPriceConfigIfNeeded()
                    } else if (shouldForceZaffariIntegration()) {
                        ensureZaffariDefaultPriceConfigIfNeeded()
                    } else if (shouldForceKompraoIntegration()) {
                        ensureKompraoDefaultPriceConfigIfNeeded()
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
                    scheduleReturnToMedia(8000L, "fallback")
                    return@launch
                }
            }

            val demo = runCatching { demoRepo.getDemoProduct(ean) }.getOrNull()
            if (demo != null) {
                showDemoProduct(demo)
                scheduleReturnToMedia(8000L, "fallback")
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
                // Fase 0 da migração para Layout Universal (prova de conceito): mesmos dados do
                // test_zaf_full, só trocando o layout para validar o ScrollView com N slots.
                "test_universal" -> {
                    mockProduct = PriceProduct(
                        id = "UNIV001", ean = "7891000315507", description = "CHOCOLATE LACTA AO LEITE 80G",
                        price = 5.99, originalPrice = null, clubPrice = 2.19, priceClub = 2.19, priceWholesale = 2.09,
                        offer = PriceOffer(enabled = true, title = "A PARTIR DE 27 UN", description = null, secondUnit = 2.09, type = "bulk"),
                        priceSlots = listOf(
                            ProductPriceSlot(label = "PREÇO/UN", value = 5.99, field = "price", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLIENTE CLUBE", value = 2.19, field = "price_club", isPromo = false, isClub = true),
                            ProductPriceSlot(label = "A PARTIR DE 27 UN", value = 4.49, field = "price_wholesale", isPromo = false, isClub = false),
                            ProductPriceSlot(label = "CLUBE 27 UN", value = 2.09, field = "price_club", isPromo = false, isClub = true),
                        ),
                        xmlLayoutType = "universal", packs = emptyList(), theme = null, offline = false
                    )
                    true
                }
                // Layout vertical (retrato) pensado pro terminal ET45 — mock pra validar o visual
                // (foto em cima 45% + painel escuro embaixo 55%, badges "LEVE X PAGUE Y" e o card
                // de pacote com preço por unidade) antes de decidir a seleção automática de layout
                // por device profile, que ainda não existe (hoje xmlLayoutType só vem do config do
                // servidor). Reaproveita o EAN do KitKat (já testado no produtos-imgs nesta sessão)
                // pra puxar a foto real do cache local, se já tiver sido baixada antes.
                "test_vertical" -> {
                    mockProduct = PriceProduct(
                        id = "VERT001", ean = "7891000248768", description = "CHOCOLATE KITKAT 4 FINGERS AO LEITE 41,5G",
                        price = 6.94, originalPrice = null, clubPrice = null,
                        offer = PriceOffer(enabled = true, title = "LEVE 3 PAGUE 2", description = null, secondUnit = null, type = "LEVE_PAGUE"),
                        packs = listOf(PricePack(label = "2 CAP", price = 6.94, unitPrice = 4.63)),
                        xmlLayoutType = "vertical_image_top", theme = null, offline = false
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
                scheduleReturnToMedia(8000L, "fallback")
                return@launch
            }

            val engine = priceEngine ?: return@launch
            showPriceOverlayLoading(ean)
            lastMeaningfulEventEpochMs = System.currentTimeMillis()

            val product = runCatching { engine.query(ean, cfg, isOnline()) }.getOrNull()
            if (product != null) {
                consecutiveFailCount = 0
                consecutiveFailReported = false
                showPriceOverlayProduct(product)
                val timeoutMs = if (product.clubPrice != null || product.priceClub != null) 30000L else 8000L
                scheduleReturnToMedia(timeoutMs, "fallback")
            } else {
                val offlineProduct = runCatching { engine.query(ean, cfg, isOnline = false) }.getOrNull()
                if (offlineProduct != null) {
                    consecutiveFailCount = 0
                    consecutiveFailReported = false
                    showPriceOverlayProduct(offlineProduct.copy(offline = true))
                    val timeoutMs = if (offlineProduct.clubPrice != null || offlineProduct.priceClub != null) 30000L else 8000L
                    scheduleReturnToMedia(timeoutMs, "fallback")
                } else {
                    // Falha repetida do mesmo item (item 1 do monitoramento proativo): útil pra
                    // apontar EAN com problema de cadastro/API, não pra "produto não existe" avulso.
                    if (ean == consecutiveFailEan) {
                        consecutiveFailCount++
                    } else {
                        consecutiveFailEan = ean
                        consecutiveFailCount = 1
                        consecutiveFailReported = false
                    }
                    if (consecutiveFailCount >= MAX_CONSECUTIVE_SCAN_FAILURES && !consecutiveFailReported) {
                        consecutiveFailReported = true
                        reportDeviceEvent(
                            eventType = "repeated_scan_failure",
                            ean = ean,
                            failCount = consecutiveFailCount,
                        )
                    }
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

    // Contador do toque "secreto" na engrenagem (invisível, alpha=0.0 no layout): só abre o
    // diálogo de senha depois de 5 toques seguidos dentro de 2s um do outro — evita que um toque
    // acidental na área (ela é invisível, mas continua recebendo toque) já revele o acesso restrito.
    private var settingsGearTapCount = 0
    private val resetSettingsGearTapCount = Runnable { settingsGearTapCount = 0 }

    private fun setupDevModeToggle() {
        binding.btnSettingsGear.setOnClickListener {
            settingsGearTapCount++
            android.util.Log.i("SettingsGear", "tap count=$settingsGearTapCount")
            mainHandler.removeCallbacks(resetSettingsGearTapCount)
            if (settingsGearTapCount >= 5) {
                settingsGearTapCount = 0
                showAdminAccessDialog()
            } else {
                mainHandler.postDelayed(resetSettingsGearTapCount, 2000L)
            }
        }

        binding.deviceIdWatermark.setOnLongClickListener {
            showAdminAccessDialog()
            true
        }

        // Hold de 3s (não o long-click padrão de ~500ms) no texto da versão (mantido como fallback).
        val holdRunnable = Runnable { showAdminAccessDialog() }
        binding.apkVersionWatermark.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> mainHandler.postDelayed(holdRunnable, 3000L)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(holdRunnable)
            }
            true
        }
    }

    /**
     * Diálogo de acesso ao Settings: pede a senha TOTP de 4 dígitos (válida por 1 minuto,
     * ver [TotpPasswordGenerator] e `ARGOS_OPEN_SETTINGS_CONTRACT.md`). Usa um teclado numérico
     * próprio (em vez do EditText padrão) para não abrir o teclado do Android, e mostra a
     * data/hora atual para o técnico calcular a senha do minuto no Argos.
     */
    private fun showAdminAccessDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val maxDigits = 4
        val enteredDigits = StringBuilder()

        val root = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val dateTimeLabel = TextView(this@PlayerActivity).apply {
            text = "Data/hora do aparelho:"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        root.addView(dateTimeLabel)

        val dateTimeView = TextView(this@PlayerActivity).apply {
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(dateTimeView)

        val displayView = TextView(this@PlayerActivity).apply {
            textSize = 32f
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.4f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(displayView)

        fun refreshDisplay() {
            displayView.text = (0 until maxDigits).joinToString("  ") { i ->
                if (i < enteredDigits.length) enteredDigits[i].toString() else "_"
            }
        }
        refreshDisplay()

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        val clockHandler = Handler(Looper.getMainLooper())
        val clockRunnable = object : Runnable {
            override fun run() {
                dateTimeView.text = dateFormat.format(Date())
                clockHandler.postDelayed(this, 1000L)
            }
        }
        clockHandler.post(clockRunnable)

        fun makeKey(label: String, onClick: () -> Unit) = android.widget.Button(this@PlayerActivity).apply {
            text = label
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnClickListener { onClick() }
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫"),
        )
        rows.forEach { rowLabels ->
            val row = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            rowLabels.forEach { label ->
                row.addView(
                    makeKey(label) {
                        when (label) {
                            "C" -> enteredDigits.setLength(0)
                            "⌫" -> if (enteredDigits.isNotEmpty()) enteredDigits.setLength(enteredDigits.length - 1)
                            else -> if (enteredDigits.length < maxDigits) enteredDigits.append(label)
                        }
                        refreshDisplay()
                    }
                )
            }
            root.addView(row)
        }

        val dialog = android.app.AlertDialog.Builder(this@PlayerActivity)
            .setTitle("Acesso Restrito")
            .setView(root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar") { _, _ ->
                val entered = enteredDigits.toString().trim()
                val id = if (::deviceId.isInitialized && deviceId.isNotBlank()) deviceId else DeviceIdentityManager(applicationContext).getCachedId()
                val last4 = if (id.length >= 4) id.takeLast(4) else ""
                val serialRaw = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 26) android.os.Build.getSerial() else @Suppress("DEPRECATION") android.os.Build.SERIAL
                }.getOrNull()?.trim().orEmpty()
                val last4Raw = if (serialRaw.length >= 4) serialRaw.takeLast(4) else ""
                val isSerialMatch = (last4.isNotEmpty() && entered == last4) || (last4Raw.isNotEmpty() && entered == last4Raw)
                val isTotpValid = TotpPasswordGenerator.isValid(entered, BuildConfig.ARGOS_OTP_SECRET) ||
                    (id.isNotBlank() && TotpPasswordGenerator.isValid(entered, id)) ||
                    (serialRaw.isNotBlank() && TotpPasswordGenerator.isValid(entered, serialRaw))
                val isCorrect = entered == "0408" || isSerialMatch || isTotpValid
                android.util.Log.i("AdminAccess", "entered='$entered' id='$id' last4='$last4' serialRaw='$serialRaw' last4Raw='$last4Raw' isSerialMatch=$isSerialMatch isTotpValid=$isTotpValid isCorrect=$isCorrect")
                if (isCorrect) {
                    startActivity(Intent(this@PlayerActivity, SettingsActivity::class.java))
                } else {
                    Toast.makeText(this@PlayerActivity, "Senha inválida!", Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        dialog.setOnDismissListener { clockHandler.removeCallbacks(clockRunnable) }
        dialog.show()
    }

    private fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim()
    }

    private fun applyPriceConfigFromManifestJson(manifestJson: String) {
        if (shouldForceTcServerIntegration()) {
            ensureTcServerDefaultPriceConfigIfNeeded()
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-tcserver")
            return
        }
        if (shouldForceUnidasulIntegration()) {
            ensureUnidasulDefaultPriceConfigIfNeeded()
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-unidasul")
            return
        }
        if (shouldForceCustomQueryIntegration()) {
            ensureCustomQueryDefaultPriceConfigIfNeeded()
            Log.i("MPlayerPrice", "price_config_applied forced_integration=custom_query_xml")
            return
        }
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
            ensureZaffariDefaultPriceConfigIfNeeded()
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-zaffari companyId=$companyId")
            return
        }
        if (shouldForceKompraoIntegration()) {
            ensureKompraoDefaultPriceConfigIfNeeded()
            Log.i("MPlayerPrice", "price_config_applied forced_integration=integra-komprao companyId=$companyId")
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
                cacheMinutes = 10,
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
                cacheMinutes = 10,
                steps = emptyList(),
                analyticsUploadUrl = null,
            )
        Log.i("MPlayerPrice", "price_config_default_americana_applied companyId=$companyId")
    }

    private fun shouldForceZaffariIntegration(): Boolean {
        val id = companyId?.trim().orEmpty()
        return id.equals("fd55dbdd-63da-442e-aa99-5575c0496622", ignoreCase = true)
    }

    private fun shouldForceKompraoIntegration(): Boolean {
        if (com.mupa.player.enterprise.BuildConfig.FLAVOR == "komprao") return true
        val id = companyId?.trim().orEmpty()
        return id.equals("54fc2743-5194-48b1-a002-ab42a45b834c", ignoreCase = true)
    }

    // TCServer Gertec: configurado manualmente em Configurações; quando presente,
    // tem prioridade sobre qualquer outra integração
    @Volatile private var tcServerAddress: String = ""

    private suspend fun refreshTcServerAddress() {
        tcServerAddress = runCatching {
            SettingsManager(applicationContext).getTcServerAddress()
        }.getOrDefault("")
    }

    private fun shouldForceTcServerIntegration(): Boolean = tcServerAddress.isNotBlank()

    private fun ensureTcServerDefaultPriceConfigIfNeeded() {
        if (!shouldForceTcServerIntegration()) return
        priceConfig = PriceConfig(
            integration = "integra-tcserver",
            timeoutMs = 8000L,
            // Sem cache: o TCServer é local/rápido e o preço pode mudar a qualquer momento
            cacheMinutes = 0,
            steps = emptyList(),
            analyticsUploadUrl = null,
        )
        Log.i("MPlayerPrice", "price_config_default_tcserver_applied address=$tcServerAddress")
    }

    // Importador manual do "XML da consulta" (Mupa Studio): colado em Configurações, tem
    // prioridade sobre a integração automática da empresa (igual TCServer/UnidaSul acima) — serve
    // pra testar a configuração de um cliente novo sem precisar de código nativo (PriceQueryEngine
    // só sabe lidar com steps genéricos: authenticate/lookup_price/mapping, ver PriceConfigParser).
    @Volatile private var customQueryXml: String = ""

    private suspend fun refreshCustomQueryXml() {
        customQueryXml = runCatching {
            SettingsManager(applicationContext).getCustomQueryXml()
        }.getOrDefault("")
    }

    private fun shouldForceCustomQueryIntegration(): Boolean = customQueryXml.isNotBlank()

    private fun ensureCustomQueryDefaultPriceConfigIfNeeded() {
        if (!shouldForceCustomQueryIntegration()) return
        val parsed = runCatching { PriceConfigXmlImporter.parse(customQueryXml) }.getOrNull()
        if (parsed == null) {
            Log.w("MPlayerPrice", "price_config_custom_query_xml_invalid")
            return
        }
        priceConfig = parsed
        Log.i("MPlayerPrice", "price_config_custom_query_applied integration=${parsed.integration} steps=${parsed.steps.size}")
    }

    // UnidaSul: mesmo padrão exato do TCServer acima — endereço do servidor da
    // loja configurado manualmente em Configurações (SettingsManager
    // unidasul_server_address). Achado real (2026-10-14, teste ao vivo com
    // device na rede do cliente): esse "force" nunca tinha sido escrito —
    // PriceQueryEngine.queryUnidasul() e os testes unitários já existiam e
    // funcionavam, mas nada em PlayerActivity jamais setava
    // priceConfig.integration="integra-unidasul", então toda consulta real
    // (scan no PlayerActivity) nunca chegava nessa integração — só o botão
    // "Testar" de Configurações (que bate direto no servidor, sem passar por
    // priceConfig) dava a falsa impressão de que a conexão funcionava.
    @Volatile private var unidasulServerAddress: String = ""

    private suspend fun refreshUnidasulServerAddress() {
        unidasulServerAddress = runCatching {
            SettingsManager(applicationContext).getUnidasulServerAddress()
        }.getOrDefault("")
    }

    private fun shouldForceUnidasulIntegration(): Boolean = unidasulServerAddress.isNotBlank()

    private fun ensureUnidasulDefaultPriceConfigIfNeeded() {
        if (!shouldForceUnidasulIntegration()) return
        priceConfig = PriceConfig(
            integration = "integra-unidasul",
            timeoutMs = 8000L,
            cacheMinutes = 10,
            steps = emptyList(),
            analyticsUploadUrl = null,
        )
        Log.i("MPlayerPrice", "price_config_default_unidasul_applied address=$unidasulServerAddress")
    }

    private fun ensureKompraoDefaultPriceConfigIfNeeded() {
        if (!shouldForceKompraoIntegration()) return
        priceConfig = PriceConfig(
            integration = "integra-komprao",
            timeoutMs = 9000L,
            cacheMinutes = 5,
            steps = emptyList(),
            analyticsUploadUrl = null,
        )
        Log.i("MPlayerPrice", "price_config_default_komprao_applied companyId=$companyId")
        priceEngine?.warmUpKompraoToken()
    }

    private fun ensureZaffariDefaultPriceConfigIfNeeded() {
        if (!shouldForceZaffariIntegration() && !ZAFFARI_TEST_MODE) return
        priceConfig =
            PriceConfig(
                integration = "integra-zaffari",
                timeoutMs = 9000L,
                cacheMinutes = 5,
                steps = listOf(
                    PriceStep(
                        type = "authenticate",
                        url = "https://zaffariexpress.com.br/api/login/login",
                        method = "POST",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"usuario":"mupa","password":"7hDD${'$'}%k*WJrY%4sQQY9G"}""",
                        mapping = mapOf("access_token" to "token"),
                    ),
                ),
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
        // Novo scan: invalida o timer de retorno anterior e cancela agendamentos pendentes
        overlayGeneration++
        cancelReturnToMedia()
        setPriceOverlayVisible(true)
        overlayEan = ean
        overlayRenderJob?.cancel()

        binding.priceResultRoot.visibility = View.GONE
        binding.priceLoadingContainer.visibility = View.VISIBLE
        binding.priceLoadingContainer.alpha = 0f
        binding.priceLoadingContainer.scaleX = 0.95f
        binding.priceLoadingContainer.scaleY = 0.95f
        binding.priceLoadingContainer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()

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
                // #region debug-point B:overlay-render-start
                debugOrientationEvent(
                    hypothesisId = "B",
                    location = "PlayerActivity.showPriceOverlayProduct:start",
                    msg = "overlay render job started",
                    data =
                        JSONObject()
                            .put("expectedEan", expectedEan)
                            .put("productLayoutType", product.xmlLayoutType)
                            .put("overlayVisible", binding.priceOverlay.visibility == View.VISIBLE)
                            .put("loadingVisible", binding.priceLoadingContainer.visibility == View.VISIBLE)
                            .put("orientation", resources.configuration.orientation),
                )
                // #endregion
                val engine = priceEngine
                val cfg = priceConfig

                val localFromProduct =
                    product.image
                        ?.takeIf { it.startsWith("/") }
                        ?.let { File(it) }
                        ?.takeIf { it.exists() && it.length() > 0L }
                        ?.absolutePath

                val localPath = localFromProduct ?: engine?.getLocalProductImagePathIfExists(expectedEan)

                // Pedido do usuário (2026-10-23): `product.hasArt` é só o que a ÚLTIMA consulta
                // encontrou — pode estar desatualizado (cache da arte evictado, arquivo apagado
                // etc.). Sem isso, `localPath` caía silenciosamente pra foto normal
                // (`getLocalProductImagePathIfExists`, cache SEM o sufixo "_arte") enquanto
                // `updatePriceBadge` continuava recebendo `hasArt=true` do cache — o layout de
                // arte (badge sobreposto, painel escondido) era aplicado em cima da foto normal,
                // sem a área reservada pro preço. Só confia em "tem arte" quando o arquivo que a
                // própria arte referencia (`product.image`) realmente existe agora.
                val effectiveHasArt = product.hasArt && localFromProduct != null

                // Renderiza o preço imediatamente com a imagem local (se houver);
                // o download da imagem remota acontece em segundo plano depois.
                // Respeita o toggle imageSearchEnabled (busca de imagem pode ser desligada).
                // Também rebaixa quando a imagem local está VENCIDA (cache >60min): mostra a
                // velha na hora (rápido) e atualiza com fade quando a nova chega.
                val imageSearchEnabled = runCatching { SettingsManager(applicationContext).getSettings().imageSearchEnabled }.getOrElse { true }
                val imageStale = !localPath.isNullOrBlank() && (engine?.isProductImageStale(expectedEan) == true)
                // Mesmo com foto local fresca, sem arte ainda vale a pena checar em background —
                // a arte pode ter sido gerada depois da última consulta (ver
                // PriceQueryEngine.preloadProductImageAndTheme, que só re-baixa a foto se
                // realmente precisar).
                //
                // Consulta Patrocinada (achado real de bancada, 2026-11-24): "tem prioridade
                // sobre a foto do produto" só é aplicado dentro do fetch em segundo plano — um
                // produto com arte publicitária PRÓPRIA já fresca em cache (`effectiveHasArt`)
                // pulava esse fetch inteiro e nunca via a verificação de patrocínio, ficando preso
                // no layout de arte por produto. Com patrocínio configurado neste dispositivo, o
                // fetch sempre roda, nem que seja só pra essa checagem (ela mesma é barata —
                // normalmente a peça já está pré-cacheada por precacheSponsoredLookups).
                val needsBackgroundImageFetch = imageSearchEnabled &&
                    (localPath.isNullOrBlank() || imageStale || !effectiveHasArt || engine?.hasSponsoredLookupsConfigured() == true)
                var finalImagePath: String? = localPath
                val finalTheme: PriceTheme? = product.theme

                if (finalImagePath.isNullOrBlank()) {
                    finalImagePath = runCatching { engine?.getDefaultProductImagePath() }.getOrNull()
                }

                // #region debug-point C:overlay-image-prepared
                debugOrientationEvent(
                    hypothesisId = "C",
                    location = "PlayerActivity.showPriceOverlayProduct:image",
                    msg = "overlay image/theme preflight finished",
                    data =
                        JSONObject()
                            .put("expectedEan", expectedEan)
                            .put("localPathExists", !localPath.isNullOrBlank())
                            .put("finalImagePathExists", !finalImagePath.isNullOrBlank())
                            .put("needsBackgroundImageFetch", needsBackgroundImageFetch)
                            .put("enginePresent", engine != null)
                            .put("cfgPresent", cfg != null),
                )
                // #endregion

                val prepared =
                    if (!finalImagePath.isNullOrBlank()) {
                        prepareOverlayFromImage(url = finalImagePath, theme = finalTheme)
                    } else {
                        null
                    }

                // #region debug-point D:before-main-thread-bind
                debugOrientationEvent(
                    hypothesisId = "D",
                    location = "PlayerActivity.showPriceOverlayProduct:pre-main",
                    msg = "prepared overlay before main-thread bind",
                    data =
                        JSONObject()
                            .put("expectedEan", expectedEan)
                            .put("preparedPresent", prepared != null)
                            .put("overlayVisible", binding.priceOverlay.visibility == View.VISIBLE)
                            .put("loadingVisible", binding.priceLoadingContainer.visibility == View.VISIBLE),
                )
                // #endregion

                withContext(Dispatchers.Main) {
                    // #region debug-point E:main-thread-guard
                    if (overlayEan != expectedEan || binding.priceOverlay.visibility != View.VISIBLE) {
                        debugOrientationEvent(
                            hypothesisId = "E",
                            location = "PlayerActivity.showPriceOverlayProduct:guard",
                            msg = "main-thread bind aborted by guard",
                            data =
                                JSONObject()
                                    .put("expectedEan", expectedEan)
                                    .put("overlayEan", overlayEan)
                                    .put("overlayVisible", binding.priceOverlay.visibility == View.VISIBLE),
                        )
                        return@withContext
                    }
                    // #endregion

                    val layoutType = product.xmlLayoutType ?: priceConfig?.layout?.xmlLayoutType ?: "split"
                    inflateLayoutForProduct(layoutType)
                    // Adapta imediatamente à orientação atual (portrait/landscape)
                    applyPriceOverlayLayout()

                    val nameText = binding.priceResultRoot.findViewById<TextView>(R.id.priceNameText)
                    val nameSecondLineText = binding.priceResultRoot.findViewById<TextView>(R.id.priceNameSecondLineText)
                    val smartCaptionText = binding.priceResultRoot.findViewById<TextView>(R.id.priceSmartCaption)
                    val codeText = binding.priceResultRoot.findViewById<TextView>(R.id.priceCodeText)
                    val subtitleText = binding.priceResultRoot.findViewById<TextView>(R.id.priceSubtitleText)
                    val offlineBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfflineBadge)
                    val offerText = binding.priceResultRoot.findViewById<TextView>(R.id.priceOfferText)
                    val offerContainer = binding.priceResultRoot.findViewById<View>(R.id.priceOfferContainer)
                    val packsContainer = binding.priceResultRoot.findViewById<ViewGroup>(R.id.pricePacksContainer)
                    val priceContainer = binding.priceResultRoot.findViewById<LinearLayout>(R.id.price_container)

                    if (nameText != null) {
                        applyProductName(nameText, nameSecondLineText, product.description.orEmpty())
                    }
                    applySmartCaption(smartCaptionText, product.description)
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

                    renderOffer(product, offerContainer, offerText)
                    renderPacks(product.packs, packsContainer)

                    val updatedProduct = product.copy(theme = finalTheme, hasArt = effectiveHasArt)
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

                    BrandTypography.applyBrandTypography(binding.priceResultRoot, this@PlayerActivity)

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
                        updatePriceBadge(imgView, updatedProduct, updatedProduct.hasArt, prepared, lastSponsorRegion, lastIsSponsoredLookup, lastSponsorProductPhotoPath)
                    } else {
                        val imgView = binding.priceResultRoot.findViewById<ImageView>(R.id.priceProductImage)
                        imgView?.setImageResource(com.mupa.player.enterprise.R.drawable.ic_mplayer)
                        updatePriceBadge(imgView, updatedProduct, false, null, lastSponsorRegion, lastIsSponsoredLookup, lastSponsorProductPhotoPath)
                    }

                    // Mostra o spinner por cima da foto enquanto a imagem real ainda não chegou
                    // (needsBackgroundImageFetch) — a busca em segundo plano logo abaixo o esconde
                    // quando a foto de verdade (ou a falha) chegar.
                    binding.priceResultRoot.findViewWithTag<ProgressBar>("priceImageLoadingSpinner")
                        ?.visibility = if (needsBackgroundImageFetch) View.VISIBLE else View.GONE

                    applyThemeColors(binding.priceResultRoot, updatedProduct, priceConfig?.layout)

                    binding.priceResultRoot.alpha = 0f
                    binding.priceResultRoot.visibility = View.VISIBLE

                    if (binding.priceLoadingContainer.visibility == View.VISIBLE) {
                        binding.priceLoadingContainer.animate()
                            .alpha(0f)
                            .setDuration(120)
                            .withEndAction {
                                binding.priceLoadingContainer.visibility = View.GONE
                            }
                            .start()
                    } else {
                        binding.priceLoadingContainer.visibility = View.GONE
                    }

                    binding.priceResultRoot.animate()
                        .alpha(1f)
                        .setDuration(220)
                        .start()

                    // #region debug-point F:bind-complete
                    debugOrientationEvent(
                        hypothesisId = "F",
                        location = "PlayerActivity.showPriceOverlayProduct:bound",
                        msg = "overlay main-thread bind completed",
                        data =
                            JSONObject()
                                .put("expectedEan", expectedEan)
                                .put("layoutType", layoutType)
                                .put("resultVisible", binding.priceResultRoot.visibility == View.VISIBLE)
                                .put("loadingVisible", binding.priceLoadingContainer.visibility == View.VISIBLE)
                                .put("orientation", resources.configuration.orientation),
                    )
                    binding.priceOverlay.postDelayed({
                        debugOrientationEvent(
                            hypothesisId = "F",
                            location = "PlayerActivity.showPriceOverlayProduct:bound+500ms",
                            msg = "overlay state 500ms after bind",
                            data =
                                JSONObject()
                                    .put("expectedEan", expectedEan)
                                    .put("resultVisible", binding.priceResultRoot.visibility == View.VISIBLE)
                                    .put("resultAlpha", binding.priceResultRoot.alpha.toDouble())
                                    .put("loadingVisible", binding.priceLoadingContainer.visibility == View.VISIBLE)
                                    .put("loadingAlpha", binding.priceLoadingContainer.alpha.toDouble())
                                    .put("orientation", resources.configuration.orientation),
                        )
                    }, 500L)
                    // #endregion

                    playBeep()

                    // Quando a integração traz slots com rótulo próprio (ex: Komprão, cuja API
                    // já devolve a label pronta), a fala é montada a partir desses rótulos.
                    val slotsParaFala = product.priceSlots
                    if (!slotsParaFala.isNullOrEmpty()) {
                        speakSlotsIfPossible(product.ean, slotsParaFala)
                    } else {
                        speakPriceIfPossible(product)
                    }

                    animateOverlayWidgets()
                    playShineSweep()

                    // Entrada escalonada dos boxes de preço (multi_price)
                    if (priceContainer != null && layoutType !in specificLayouts) {
                        for (i in 0 until priceContainer.childCount) {
                            val child = priceContainer.getChildAt(i)
                            child.animate().cancel()
                            child.alpha = 0f
                            child.translationY = 34f
                            child.scaleX = 0.92f
                            child.scaleY = 0.92f
                            child.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(320)
                                .setStartDelay((140 + i * 110).toLong())
                                .setInterpolator(OvershootInterpolator(1.15f))
                                .start()
                        }
                    }
                }

                // Download da imagem do produto em segundo plano: salva na pasta local
                // e atualiza a tela com fade se o produto ainda estiver visível
                if (needsBackgroundImageFetch && engine != null && cfg != null) {
                    launch(Dispatchers.IO) {
                        val preloaded =
                            runCatching {
                                engine.preloadProductImageAndTheme(
                                    ean = expectedEan,
                                    config = cfg,
                                    isOnline = isOnline(),
                                )
                            }.onFailure {
                                Log.w("MPlayerScan", "bg_image_fetch_exception ean=$expectedEan err=${it.javaClass.simpleName}:${it.message}")
                            }.getOrNull()
                        val downloadedPath = preloaded?.path
                        val lateTheme = preloaded?.theme
                        val lateHasArt = preloaded?.hasArt ?: false
                        // Telas Patrocinadas: este fetch em segundo plano é a fonte de verdade da
                        // região (ver preloadProductImageAndTheme) — guarda pra o render INICIAL
                        // do próximo scan já nascer com a posição certa, sem esperar este fetch.
                        lastSponsorRegion = preloaded?.sponsorRegion
                        lastIsSponsoredLookup = preloaded?.isSponsoredLookup ?: false
                        lastSponsorProductPhotoPath = preloaded?.productPhotoPath
                        if (downloadedPath.isNullOrBlank()) {
                            // Falhou (ou o produto ficou sem imagem mesmo) — esconde o spinner pra
                            // não girar pra sempre; fica só o placeholder já exibido antes.
                            withContext(Dispatchers.Main) {
                                if (overlayEan == expectedEan && binding.priceOverlay.visibility == View.VISIBLE) {
                                    binding.priceResultRoot.findViewWithTag<ProgressBar>("priceImageLoadingSpinner")
                                        ?.visibility = View.GONE
                                }
                            }
                            return@launch
                        }

                        val preparedLate = prepareOverlayFromImage(url = downloadedPath, theme = lateTheme ?: product.theme)

                        withContext(Dispatchers.Main) {
                            if (overlayEan != expectedEan || binding.priceOverlay.visibility != View.VISIBLE) return@withContext
                            binding.priceResultRoot.findViewWithTag<ProgressBar>("priceImageLoadingSpinner")?.visibility = View.GONE
                            if (preparedLate != null) {
                                applyOverlayColors(
                                    dominant = preparedLate.dominant,
                                    dark = preparedLate.dark,
                                    light = preparedLate.light,
                                    vibrant = preparedLate.secondary,
                                    text = preparedLate.text,
                                )
                            }
                            val imgView = binding.priceResultRoot.findViewById<ImageView>(R.id.priceProductImage)
                            preparedLate?.drawable?.let { d ->
                                imgView?.apply {
                                    animate().cancel()
                                    alpha = 0f
                                    scaleX = 0.96f
                                    scaleY = 0.96f
                                    setImageDrawable(d)
                                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
                                }
                            }
                            updatePriceBadge(imgView, product.copy(hasArt = lateHasArt), lateHasArt, preparedLate, lastSponsorRegion, lastIsSponsoredLookup, lastSponsorProductPhotoPath)
                        }
                    }
                }
            }
        // #region debug-point G:overlay-render-completion
        overlayRenderJob?.invokeOnCompletion { throwable ->
            debugOrientationEvent(
                hypothesisId = "G",
                location = "PlayerActivity.showPriceOverlayProduct:completion",
                msg = "overlay render job finished",
                data =
                    JSONObject()
                        .put("expectedEan", expectedEan)
                        .put("cancelled", throwable is kotlinx.coroutines.CancellationException)
                        .put("throwable", throwable?.javaClass?.simpleName ?: "")
                        .put("message", throwable?.message ?: ""),
            )
        }
        // #endregion
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
                    val nameSecondLineText = binding.priceResultRoot.findViewById<TextView>(R.id.priceNameSecondLineText)
                    val smartCaptionText = binding.priceResultRoot.findViewById<TextView>(R.id.priceSmartCaption)
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

                    if (nameText != null) {
                        applyProductName(nameText, nameSecondLineText, product.nome)
                    }
                    applySmartCaption(smartCaptionText, product.nome)
                    subtitleText?.visibility = View.GONE
                    codeText?.text = "Código: ${product.ean}"

                    offlineBadge?.visibility = View.GONE

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

                    renderOffer(demoPriceProduct, offerContainer, offerText)

                    val accentHex = if (priceConfig?.layout?.colorMode == "solid" && !priceConfig?.layout?.solidColor.isNullOrEmpty()) {
                        priceConfig?.layout?.solidColor
                    } else {
                        null
                    }
                    val parsedColor = accentHex?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: prepared.dominant

                    if (priceContainer != null) {
                        populatePriceSlots(priceContainer, priceConfig?.layout, demoPriceProduct, parsedColor)
                    }

                    BrandTypography.applyBrandTypography(binding.priceResultRoot, this@PlayerActivity)

                    playBeep()
                    speakPriceIfPossible(demoPriceProduct)

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
            cancelReturnToMedia()
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
                // Se o TTS estiver ativo e falando, aguarda terminar antes de fechar o painel
                while (ttsReady && tts?.isSpeaking == true) {
                    delay(200L)
                }
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
        root.findViewById<TextView>(R.id.priceNameSecondLineText)?.setTextColor(adjustAlpha(nameTextColor, 0.85f))
        root.findViewById<TextView>(R.id.priceSubtitleText)?.setTextColor(adjustAlpha(nameTextColor, 0.90f))
        root.findViewById<TextView>(R.id.priceSmartCaption)?.setTextColor(adjustAlpha(nameTextColor, 0.75f))

        // Badges de oferta (LEVE 3 / PAGUE 2 etc.) sempre em vermelho
        val offerRed = Color.parseColor("#ef4444")
        root.findViewById<View>(R.id.priceLeftBadgeText)?.let {
            it.background = roundedBg(offerRed, radiusDp = 8f)
            (it as? TextView)?.setTextColor(Color.WHITE)
        }
        root.findViewById<View>(R.id.priceRightBadgeText)?.let {
            it.background = roundedBg(offerRed, radiusDp = 8f)
            (it as? TextView)?.setTextColor(Color.WHITE)
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
        // Reposiciona os painéis de qualquer layout de consulta (split, de_por, multi_price…)
        // conforme a orientação, manipulando os LayoutParams diretamente (mais confiável que
        // ConstraintSet neste caso). Retrato = imagem em cima + infos embaixo; paisagem = lado a lado.
        val root = binding.priceResultRoot
        val left = root.findViewById<View>(R.id.priceLeftPanel) ?: return
        val right = root.findViewById<View>(R.id.priceRightPanel) ?: return
        val guide = root.findViewById<androidx.constraintlayout.widget.Guideline>(R.id.priceLandscapeSplitGuide) ?: return

        val lp = left.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val rp = right.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val gp = guide.layoutParams as? ConstraintLayout.LayoutParams ?: return

        val parent = ConstraintLayout.LayoutParams.PARENT_ID
        val unset = ConstraintLayout.LayoutParams.UNSET
        val match = 0 // MATCH_CONSTRAINT
        val isPortrait = resources.displayMetrics.heightPixels >= resources.displayMetrics.widthPixels

        // Zera todas as âncoras dos dois painéis para reconstruir sem sobras
        for (p in listOf(lp, rp)) {
            p.topToTop = unset; p.topToBottom = unset
            p.bottomToBottom = unset; p.bottomToTop = unset
            p.startToStart = unset; p.startToEnd = unset
            p.endToEnd = unset; p.endToStart = unset
        }

        if (isPortrait) {
            gp.orientation = ConstraintLayout.LayoutParams.HORIZONTAL
            guide.setGuidelinePercent(0.38f)

            // Imagem (right) EM CIMA
            rp.width = match; rp.height = match
            rp.topToTop = parent; rp.startToStart = parent; rp.endToEnd = parent
            rp.bottomToBottom = R.id.priceLandscapeSplitGuide

            // Infos (left) EMBAIXO
            lp.width = match; lp.height = match
            lp.topToTop = R.id.priceLandscapeSplitGuide
            lp.bottomToBottom = parent; lp.startToStart = parent; lp.endToEnd = parent
        } else {
            gp.orientation = ConstraintLayout.LayoutParams.VERTICAL
            // Pedido do usuário (2026-11-17): 60% pra descrição/preço (esquerda), 40% pra imagem
            // (direita) — antes era 45/55, espaço curto demais pro preço com Dólar/Peso.
            guide.setGuidelinePercent(0.60f)

            // Infos (left) à ESQUERDA
            lp.width = match; lp.height = match
            lp.topToTop = parent; lp.bottomToBottom = parent; lp.startToStart = parent
            lp.endToStart = R.id.priceLandscapeSplitGuide

            // Imagem (right) à DIREITA
            rp.width = match; rp.height = match
            rp.topToTop = parent; rp.bottomToBottom = parent; rp.endToEnd = parent
            rp.startToEnd = R.id.priceLandscapeSplitGuide
        }

        guide.layoutParams = gp
        left.layoutParams = lp
        right.layoutParams = rp
        Log.i("MPlayerLayout", "applyPriceOverlayLayout isPortrait=$isPortrait")
        root.requestLayout()
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
            // Bug real (2026-11-17, Oral-B): a cor de destaque extraída da imagem às vezes sai
            // igual (ou quase) ao fundo do painel — que pode ser BRANCO (priceRightPanel) ou
            // escuro, dependendo do layout/tema. Uma borda clara só resolvia contra fundo
            // escuro; contra o painel branco ela ficava invisível também (branco sobre branco).
            // Cinza médio semi-transparente dá contorno visível nos dois casos.
            setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.argb(130, 140, 140, 140))
        }
    }

    private var artLetterboxBackground: android.graphics.drawable.Drawable? = null

    /**
     * Preenche as tarjas (letterbox) deixadas pelo FIT_CENTER com um degradê que nasce na cor real
     * da borda da arte e escurece até a borda da tela — sem esticar nem cortar a imagem (pedido do
     * usuário, 2026-11-19: o SK315 em pé tinha faixas brancas em cima/embaixo com a arte 1200x1920
     * numa tela 1080x1920). Funciona nos dois eixos: tarja vertical (cima/baixo) ou horizontal
     * (esquerda/direita), conforme a proporção da tela. Sem arte, remove só o fundo que ele mesmo pôs.
     */
    private fun applyArtLetterboxGradient(imgView: ImageView, hasArt: Boolean) {
        if (!hasArt) {
            if (artLetterboxBackground != null && imgView.background === artLetterboxBackground) imgView.background = null
            artLetterboxBackground = null
            return
        }
        val bmp = (imgView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val bw = bmp.width
        val bh = bmp.height
        if (bw < 2 || bh < 2) return
        val src = try {
            if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        } catch (_: Throwable) { null } ?: return

        fun avg(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = src.getPixel(x, y)
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                    x += 4
                }
                y += 2
            }
            return if (n == 0) Color.BLACK else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

        val band = maxOf(2, minOf(bw, bh) / 40)
        val first: Int; val last: Int
        val vertical: Boolean
        // Eixo da tarja: se a tela é mais "alta" que a arte, sobra em cima/embaixo; senão, nas laterais.
        val viewW = imgView.width.takeIf { it > 0 } ?: imgView.resources.displayMetrics.widthPixels
        val viewH = imgView.height.takeIf { it > 0 } ?: imgView.resources.displayMetrics.heightPixels
        vertical = viewW.toFloat() / viewH <= bw.toFloat() / bh
        if (vertical) {
            first = avg(0, 0, bw, band); last = avg(0, bh - band, bw, bh)
        } else {
            first = avg(0, 0, band, bh); last = avg(bw - band, 0, bw, bh)
        }
        if (src !== bmp) src.recycle()

        val shape = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
        shape.shaderFactory = object : android.graphics.drawable.ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val scale = minOf(width.toFloat() / bw, height.toFloat() / bh)
                val total = if (vertical) height.toFloat() else width.toFloat()
                val art = (if (vertical) bh else bw) * scale
                val f = (((total - art) / 2f) / total).coerceIn(0.001f, 0.49f)
                val colors = intArrayOf(darken(first, 0.45f), first, last, darken(last, 0.45f))
                val pos = floatArrayOf(0f, f, 1f - f, 1f)
                return if (vertical) android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), colors, pos, Shader.TileMode.CLAMP)
                else android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f, colors, pos, Shader.TileMode.CLAMP)
            }
        }
        artLetterboxBackground = shape
        imgView.background = shape
    }

    /**
     * Adiciona (ou remove) um badge de preço sobreposto sobre [imgView], usado somente quando
     * a imagem exibida é a arte publicitária gerada por IA ([hasArt] = true) — a arte já reserva
     * o canto inferior esquerdo livre de elementos justamente para este badge (ver backend
     * produtos-imgs: gerar_arte_publicitaria/ARTE_PROMPT_TEMPLATE). As cores do badge vêm da
     * paleta já extraída da própria imagem ([prepared]), para sempre combinar com a arte exibida.
     * Sem arte, remove qualquer badge existente e não faz mais nada — comportamento inalterado.
     */
    /** As N primeiras palavras de [text] em negrito numa 1ª linha, o resto (peso base do
     * TextView, normalmente NORMAL) FORÇADO pra uma 2ª linha — usado pelo nome do produto na
     * Consulta Patrocinada (pedido do usuário, 2026-11-24: "quebrar as 3 primeiras palavras, o
     * restante [na] segunda linha"). Quebra explícita (não só `maxWidth`/wrap natural) porque o
     * pedido é especificamente 3 palavras na linha 1 — `maxWidth` (aplicado separadamente no
     * TextView) ainda cuida de nenhuma das duas linhas passar da largura do oval, quebrando mais
     * se precisar. */
    private fun boldFirstWordsOnFirstLine(text: String, wordCount: Int): CharSequence {
        if (text.isBlank() || wordCount <= 0) return text
        val words = text.trim().split(Regex("\\s+"))
        if (words.size <= wordCount) {
            return SpannableString(text).apply {
                setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val firstLine = words.take(wordCount).joinToString(" ")
        val secondLine = words.drop(wordCount).joinToString(" ")
        val combined = "$firstLine\n$secondLine"
        return SpannableString(combined).apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, firstLine.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updatePriceBadge(
        imgView: ImageView?,
        product: PriceProduct,
        hasArt: Boolean,
        prepared: PreparedOverlay?,
        // Telas Patrocinadas (2026-11-17): quando uma campanha patrocinada está ativa, a região
        // reservada pro badge vem do servidor (ver PriceQueryEngine.SponsorRegion), em vez das
        // constantes fixas calibradas pra arte por produto (produtos-imgs gera sempre no mesmo
        // canvas 1280x800/1200x1920). Nula = comportamento de hoje, sem nenhuma mudança.
        sponsorRegion: PriceQueryEngine.SponsorRegion? = null,
        // Consulta Patrocinada (2026-11-24, 2ª rodada): true quando a arte em tela é uma peça
        // genérica de marca (sem nome/foto do produto embutidos) — diferente de Telas
        // Patrocinadas, aqui o badge precisa compor foto+nome+preço reais dentro de
        // [sponsorRegion], não só o preço.
        isSponsoredLookup: Boolean = false,
        productPhotoPath: String? = null,
    ) {
        if (imgView == null) return
        val parent = imgView.parent as? ViewGroup ?: return

        // Remove qualquer badge de uma renderização anterior deste mesmo produto (o render
        // inicial e o fade-in tardio da imagem podem chamar esta função duas vezes em sequência).
        for (i in parent.childCount - 1 downTo 0) {
            val childTag = parent.getChildAt(i).tag
            if (childTag == PRICE_ART_BADGE_TAG || childTag == PRICE_ART_CURRENCY_TAG || childTag == PRICE_ART_SPONSORED_PHOTO_TAG) {
                parent.removeViewAt(i)
            }
        }

        // Com arte, TUDO do layout original (painel esquerdo, barras de preço nativas etc.) some
        // — a própria arte já traz nome + preço embutidos — e a imagem ocupa a tela toda, borda a
        // borda, cobrindo qualquer outro elemento por baixo. Sem arte, cada view volta exatamente
        // ao estado (constraints + padding) que tinha antes desta função mexer nela — guardado na
        // 1ª vez, então funciona igual em qualquer um dos vários layouts de preço, não só no
        // split de duas colunas.
        val leftPanel = binding.priceResultRoot.findViewById<View>(R.id.priceLeftPanel)
        leftPanel?.visibility = if (hasArt) View.GONE else View.VISIBLE
        val rightPanel = binding.priceResultRoot.findViewById<View>(R.id.priceRightPanel)
        rightPanel?.let { makeFullBleed(it, hasArt) }

        // FIT_CENTER em vez de CENTER_CROP (pedido do usuário): a arte NUNCA pode passar das
        // bordas da tela — CENTER_CROP cobre a tela toda mas corta simetricamente o que sobra da
        // proporção (a arte é sempre 1280x800 — resolução real do terminal físico, mas pode não
        // bater exata em terminais com outra resolução),
        // e esse corte já comeu conteúdo real (ex.: os ícones de benefício encostados na borda
        // direita ficavam parcialmente cortados numa tela 1280x800). FIT_CENTER garante a arte
        // inteira sempre visível, com uma tarja (letterbox) em vez de corte quando a proporção não
        // bate — e como o fator de escala do FIT_CENTER é sempre <= o do CENTER_CROP (escala pelo
        // menor lado, não pelo maior), nunca amplia mais do que amplicava antes, ou seja, nunca
        // piora a qualidade percebida.
        imgView.scaleType = ImageView.ScaleType.FIT_CENTER
        // Consulta Patrocinada (2026-11-24, achado real de bancada): a peça tem áreas com
        // transparência de verdade (ex. um recorte oval) — a tarja/gradiente escuro desta função
        // pintava `imgView.background`, que vaza por trás de qualquer pixel transparente da PNG,
        // revelando preto em vez do branco do próprio priceRightPanel. Pulando aqui (como se
        // hasArt=false só pra esta chamada) resolve sem tocar no caso de arte por produto/Telas
        // Patrocinadas, que precisam da tarja pra letterbox.
        applyArtLetterboxGradient(imgView, hasArt && !isSponsoredLookup)

        if (!hasArt) return
        val price = product.pricePromotional ?: product.price ?: return
        if (price <= 0.0) return

        // Layout vertical (terminal em pé, ver price_check_vertical_image_top/bottom.xml) usa uma
        // arte com composição totalmente diferente da horizontal — cena em tela cheia com painel
        // de nome no topo, reservando (SEM desenhar nada) o canto inferior esquerdo pro preço (ver
        // compor_arte_vertical no produtos-imgs). Detecção estrutural (a guideline
        // 'priceImageSplitGuide' só existe nesses dois layouts), calculada aqui em cima porque
        // decide o estilo do card de preço logo abaixo (cartão branco próprio, texto na cor de
        // destaque do produto).
        val isVerticalArtLayout = binding.priceResultRoot.findViewById<View>(R.id.priceImageSplitGuide) != null

        // "secondary" é a cor vibrante/saturada da paleta (Palette.getVibrantColor, com
        // getMutedColor só como reserva) — usada ANTES de "dominant" (que é só a cor mais
        // frequente da imagem, quase sempre um tom neutro de fundo) pra o card de preço se
        // destacar de verdade em vez de ficar esmaecido/sem graça.
        val badgeColor = prepared?.secondary ?: prepared?.dominant ?: Color.parseColor("#DC2626")
        // No vertical o preço é desenhado sobre o cartão branco do próprio app (ver o container
        // abaixo) — a cor do texto é o acento do produto (sem precisar de idealTextColor, que
        // existe pra garantir contraste contra um fundo variável; aqui o fundo é sempre branco).
        //
        // Consulta Patrocinada (2026-11-24, 2ª rodada): aqui não existe caixa colorida nem
        // "fundo sempre branco conhecido" — o nome/preço são compostos DIRETO sobre a peça da
        // marca (ver referência visual do usuário: Nestlé/Colgate), sem nenhum card por baixo.
        // `badgeColor` vem da paleta DA PEÇA PATROCINADA (não do produto), então não serve de
        // acento aqui — usa um vermelho de preço fixo, igual ao fallback padrão de sempre.
        val textColor = when {
            isSponsoredLookup -> Color.parseColor("#DC2626")
            isVerticalArtLayout -> badgeColor
            else -> idealTextColor(badgeColor)
        }
        val sponsoredLookupNameColor = Color.parseColor("#1A1A2E")

        // Até 3 níveis de preço (ex.: "Preço Normal" + "OFERTA" + "Oferta Clube K") — o mesmo
        // `prices[]` que a API do Komprão retorna. Era `take(2)` até um produto real (Perdigão
        // Pizza Calabresa Moída, EAN 7891515555917) mostrar que isso descartava silenciosamente
        // a 3ª faixa (tipicamente a oferta clube, o preço mais baixo dos três) sempre que a API
        // retornava PRECO_NORMAL + PRECO_PDV + PRECO_CLUBE_KOCH juntos. Sem priceSlots
        // (integrações mais simples), cai no preço único de sempre.
        // Mesma dedup + ordem de populatePriceSlots/speakSlotsIfPossible (ver comentários lá):
        // produto em oferta aparece primeiro, e o box de preço cheio não se repete quando já
        // aparece riscado dentro do box de oferta — sem isso, com oferta + 2 moedas o take(3)
        // cortava silenciosamente uma das conversões (achado real, 2026-10-21, EAN 7891010980061:
        // só o dólar aparecia, o peso ficava de fora).
        // Pedido do usuário (2026-11-17): Dólar/Peso não entram empilhados dentro do card de
        // oferta/clube (são conversão de unidade, não "mais uma faixa de preço") — saem como
        // caixas vermelha/azul à parte, igual ao padrão sem arte (ver buildArteCurrencyBadge).
        fun isCurrencyConversionSlot(s: ProductPriceSlot) = s.field == "price_usd" || s.field == "price_ars"
        val currencySlots = product.priceSlots?.filter { isCurrencyConversionSlot(it) && it.value > 0.0 }.orEmpty()
        val dedupedArteSlots = product.priceSlots?.filter { it.value > 0.0 && !isCurrencyConversionSlot(it) }?.let { s ->
            val promoFromValues = s.filter { it.isPromo }.mapNotNull { it.fromValue }
            if (promoFromValues.isEmpty()) s else s.filterNot { !it.isPromo && !it.isClub && it.value in promoFromValues }
        }?.sortedBy { if (it.isPromo) 0 else 1 }
        val slots = dedupedArteSlots?.take(3)
        // Pedido do usuário (2026-10-23, EAN de teste Topz Bastonetes): o dedup acima remove o
        // slot "Preço Normal" quando ele é igual ao `fromValue` da oferta, assumindo que ele
        // continua visível riscado DENTRO do box de oferta — só que, ao contrário de
        // `populatePriceSlots` (tem `slotFromPrice`), este badge nunca desenhava esse "De: X"
        // riscado. Resultado real: o preço normal sumia da tela por completo, sem aparecer em
        // lugar nenhum. Agora cada linha carrega seu `fromValue` pra desenhar o riscado abaixo.
        val rows: List<ArteBadgeRow> =
            if (!slots.isNullOrEmpty()) {
                slots.map { ArteBadgeRow(it.label, it.value, it.currencySymbol, it.fromValue) }
            } else {
                listOf(ArteBadgeRow(null, price, "R$", null))
            }

        // Pedido do usuário (2026-10-23): com 3 faixas de preço, o card (ancorado pelo TOPO,
        // ver cálculo de `yDesejado`/`yMaximo` abaixo) ficava alto o bastante pra `yMaximo`
        // empurrar o card pra cima da área reservada, invadindo o nome do produto — igual ao
        // mesmo problema já corrigido no layout sem arte (`emphasizePriceSlot`/`compact`). Reduz
        // 30% a fonte do preço (moeda/inteiro/decimal) em TODAS as linhas quando há 3 faixas.
        val compactShrink = if (rows.size >= 3) 0.7f else 1.0f

        // Consulta Patrocinada (pedido do usuário, 2026-11-24, 3ª rodada): "pode dobrar o
        // tamanho" das fontes, só neste modo — não mexe no badge de arte por produto/Telas
        // Patrocinadas, que já têm seus tamanhos calibrados.
        val sponsoredLookupFontScale = if (isSponsoredLookup) 2f else 1f

        // 5ª rodada: uma 1ª tentativa reduziu a foto 30% (150dp→105dp), pensando que o estouro de
        // largura vinha do tamanho da foto — na verdade era o nome sem `maxWidth` (corrigido
        // abaixo, em `sponsoredLookupTextMaxWidthPx`, que já desconta o espaço da foto do texto).
        // 7ª rodada (pedido do usuário, 2026-11-24): "ainda não está legal"/"pequena demais" —
        // volta pro tamanho base de 150dp, já que a causa real do estouro foi corrigida à parte.
        //
        // 6ª rodada (pedido do usuário, com imagem de exemplo: oferta + preço normal, 2 faixas):
        // "diminuir 20% quando tiver mais de um preço" — com `rows.size > 1` o card fica bem mais
        // alto (2 blocos de preço + divisor), e a foto no mesmo tamanho de sempre ficava
        // desproporcional perto dele. 150dp * 0.8 = 120dp.
        // 8ª rodada (pedido do usuário, 2026-11-26): "+50% no tamanho, atrás da arte do
        // patrocínio" — a foto deixa de ser filha de `container` (ver abaixo) e passa a ser uma
        // view própria, inserida ATRÁS de `imgView` (índice 0 de `parent`); com a foto maior,
        // o que passar da área branca reservada da peça fica coberto pelos pixels opacos da
        // própria arte (o recorte transparente é o que revela a foto), em vez de aparecer por
        // cima dela.
        val sponsoredLookupPhotoSizeDp = ((if (rows.size > 1) (150 * 0.8) else 150.0) * 1.5).toInt()

        // Nome de produto comprido (ex. "LEITE CONDENSADO TRADICIONAL MOCA LATA 395G") sem
        // largura máxima estourava a tela — `WRAP_CONTENT` só quebra linha (maxLines=2) quando a
        // view tem um teto de largura real pra "estourar" contra. Estimativa a partir da fração
        // de tela que [sponsorRegion] reserva (a peça ocupa a tela toda, full-bleed, então a
        // largura da região ≈ largura em pixels), descontando o espaço da foto à esquerda.
        val sponsoredLookupTextMaxWidthPx = if (isSponsoredLookup) {
            val screenWidthPx = resources.displayMetrics.widthPixels
            val regionWidthPx = sponsorRegion?.let { ((it.x1 - it.x0) * screenWidthPx).toInt() } ?: screenWidthPx
            (regionWidthPx - dpToPx(sponsoredLookupPhotoSizeDp) - dpToPx(18) - dpToPx(48)).coerceAtLeast(dpToPx(160))
        } else {
            Int.MAX_VALUE
        }

        val container = LinearLayout(this).apply {
            tag = PRICE_ART_BADGE_TAG
            // Consulta Patrocinada (pedido do usuário, 2026-11-24, 4ª rodada, com imagem de
            // referência): foto do produto ao LADO do nome/preço, não empilhada em cima — por
            // isso HORIZONTAL só neste modo (foto + coluna de texto), igual ao exemplo
            // (Colgate à esquerda, nome/preço à direita).
            orientation = if (isSponsoredLookup) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isSponsoredLookup) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.CENTER_HORIZONTAL
            if (isSponsoredLookup) {
                // Consulta Patrocinada: SEM caixa colorida — a peça já traz seu próprio fundo
                // (branco, na referência do usuário) e a composição foto+nome+preço fica direto
                // em cima dele, igual ao exemplo (Nestlé/Colgate). Só padding, pra não grudar a
                // foto/texto um no outro nem nas bordas da área reservada.
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            } else if (isVerticalArtLayout) {
                // A arte vertical só RESERVA o espaço do preço (canto inferior esquerdo) e não
                // desenha caixa nenhuma (pedido do usuário, 05/11/2026) — o cartão branco com
                // sombra é desenhado aqui, pelo app, do tamanho do conteúdo (1 a 3 faixas de preço).
                setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
                background = roundedBg(Color.argb(242, 255, 255, 255), radiusDp = 16f)
                elevation = dpToPx(16).toFloat()
            } else {
                setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
                background = roundedBg(badgeColor, radiusDp = 16f)
                elevation = dpToPx(32).toFloat()
            }
        }

        // Consulta Patrocinada: nome/preço numa coluna vertical própria (`rowsTarget`) — a foto
        // do produto fica À ESQUERDA dela visualmente, mas (desde 2026-11-26) como VIEW PRÓPRIA
        // (`sponsoredPhotoView` abaixo), não mais filha de `container`, pra poder ficar atrás da
        // arte do patrocínio (ver posicionamento em `imgView.post` mais abaixo). Nos outros modos
        // (arte por produto/Telas Patrocinadas), `rowsTarget` é o próprio `container` vertical
        // de sempre — comportamento 100% inalterado.
        val rowsTarget: ViewGroup = if (isSponsoredLookup) {
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        } else {
            container
        }
        // Consulta Patrocinada: foto do produto como view própria (não mais filha de `container`
        // — ver [PRICE_ART_SPONSORED_PHOTO_TAG] acima), posicionada depois, atrás da arte.
        var sponsoredPhotoView: ImageView? = null
        if (isSponsoredLookup) {
            Log.d("MPlayerSponsoredLookup", "updatePriceBadge ean=${product.ean} productPhotoPath=$productPhotoPath")
            if (!productPhotoPath.isNullOrBlank()) {
                sponsoredPhotoView = ImageView(this).apply {
                    tag = PRICE_ART_SPONSORED_PHOTO_TAG
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val photoFile = File(productPhotoPath)
                    Log.d(
                        "MPlayerSponsoredLookup",
                        "loading product photo path=$productPhotoPath exists=${photoFile.exists()} size=${photoFile.length()}",
                    )
                    load(photoFile) {
                        listener(
                            onSuccess = { _, _ -> Log.d("MPlayerSponsoredLookup", "photo load SUCCESS path=$productPhotoPath") },
                            onError = { _, result -> Log.w("MPlayerSponsoredLookup", "photo load ERROR path=$productPhotoPath err=${result.throwable}") },
                        )
                    }
                }
            }
            container.addView(rowsTarget, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!product.description.isNullOrBlank()) {
                rowsTarget.addView(
                    MaterialTextView(this).apply {
                        // Pedido do usuário (2026-11-24: "quebrar as 3 primeiras palavras, o
                        // restante [na] segunda linha, não pode passar a largura do oval") —
                        // quebra EXPLÍCITA entre a 3ª e a 4ª palavra (não só wrap natural), com
                        // `maxWidth` abaixo garantindo que nenhuma das duas linhas (nem uma 3ª,
                        // se o restante ainda não couber) ultrapasse a área reservada.
                        text = boldFirstWordsOnFirstLine(product.description.orEmpty(), wordCount = 3)
                        setTextColor(sponsoredLookupNameColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * sponsoredLookupFontScale)
                        setTypeface(typeface, android.graphics.Typeface.NORMAL)
                        includeFontPadding = false
                        maxWidth = sponsoredLookupTextMaxWidthPx
                        maxLines = 3
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(6)
                    },
                )
            }
        }

        rows.forEachIndexed { index, (label, value, symbol, fromValue) ->
            if (index > 0) {
                // "match_parent" dentro de um LinearLayout "wrap_content" mede contra a TELA, não
                // contra os irmãos — estica o card inteiro. Por isso medimos a largura que o card
                // JÁ tem (só com a 1ª linha) e damos essa largura fixa ao divisor.
                rowsTarget.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val dividerWidth = rowsTarget.measuredWidth
                rowsTarget.addView(
                    View(this).apply {
                        setBackgroundColor(adjustAlpha(textColor, 0.28f))
                    },
                    LinearLayout.LayoutParams(dividerWidth, dpToPx(1)).apply {
                        topMargin = dpToPx(8)
                        bottomMargin = dpToPx(8)
                    },
                )
            }
            // A 2ª e a 3ª linha (atacado/clube) são sempre um pouco menores que a primeira, pra
            // deixar clara a hierarquia entre o preço principal e os secundários.
            val scale = if (index == 0) 1f else 0.78f
            if (!label.isNullOrBlank()) {
                rowsTarget.addView(
                    MaterialTextView(this).apply {
                        text = label
                        setTextColor(adjustAlpha(textColor, 0.9f))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale * sponsoredLookupFontScale)
                        includeFontPadding = false
                    },
                )
            }
            // "De: X" riscado (pedido do usuário, 2026-10-23) — sem isso, o preço normal
            // deduplicado acima (ver `dedupedArteSlots`) não aparecia em lugar nenhum da tela.
            if (fromValue != null && fromValue > value) {
                rowsTarget.addView(
                    MaterialTextView(this).apply {
                        text = "De: ${formatCurrency(fromValue)}"
                        setTextColor(adjustAlpha(textColor, 0.75f))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
                        includeFontPadding = false
                        paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    },
                )
            }
            // Pedido do usuário (2026-11-17): mesma fonte (Bebas Neue, antes era Poppins
            // ExtraBold) e +20% de tamanho dos preços fora da arte (ver emphasizePriceSlot) —
            // "sizeBump" é cumulativo com o scale/compactShrink já existentes, não os substitui.
            val sizeBump = 1.2f * sponsoredLookupFontScale
            val precoView = MaterialTextView(this).apply {
                text = buildPriceSpannable(
                    value = value,
                    textColor = textColor,
                    cifraoSp = (if (isVerticalArtLayout) 17f else 19.8f) * scale * compactShrink * sizeBump,
                    valueSp = (if (isVerticalArtLayout) 92f else 105.6f) * scale * compactShrink * sizeBump,
                    decimalsSp = (if (isVerticalArtLayout) 46f else 52.8f) * scale * compactShrink * sizeBump,
                    currencySymbol = symbol,
                )
                // Consulta Patrocinada (pedido do usuário, 2026-11-24, 3ª rodada): "usar as
                // fontes bebas negrito no preço" — força o estilo negrito sobre a Bebas Neue
                // (que só existe em peso regular), em vez de só cair pro fallback bold quando a
                // fonte não carrega.
                typeface = if (isSponsoredLookup) {
                    BrandTypography.bebasNeue(this@PlayerActivity)
                        ?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) }
                        ?: android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                } else {
                    BrandTypography.bebasNeue(this@PlayerActivity)
                        ?: android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                }
                includeFontPadding = false
            }
            if (isVerticalArtLayout) {
                // "R$ 4,99 un" na mesma linha (referência do usuário) — sufixo de unidade
                // discreto, cinza, alinhado pela base do preço.
                rowsTarget.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.BOTTOM
                        addView(precoView)
                        addView(
                            MaterialTextView(this@PlayerActivity).apply {
                                text = "un"
                                setTextColor(adjustAlpha(Color.BLACK, 0.45f))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
                                includeFontPadding = false
                                setPadding(dpToPx(6), 0, 0, dpToPx(10))
                            },
                        )
                    },
                )
            } else {
                rowsTarget.addView(precoView)
            }
        }

        sponsoredPhotoView?.let {
            // Índice 0 = desenhado primeiro = fica embaixo de TUDO (inclusive `imgView`, a arte do
            // patrocínio) — o recorte transparente da arte é o que revela a foto; o resto da arte
            // (opaco) cobre naturalmente o que a foto maior passar da área reservada.
            parent.addView(
                it,
                0,
                FrameLayout.LayoutParams(dpToPx(sponsoredLookupPhotoSizeDp), dpToPx(sponsoredLookupPhotoSizeDp)),
            )
        }

        parent.addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Pedido do usuário (2026-11-17): Dólar/Peso aparecem como caixas vermelha/azul à parte,
        // lado a lado — mesmas cores fixas e mesmo estilo da versão sem arte (ver populatePriceSlots/
        // styleSlotBox: US$ = #C8102E, ARS = #1E4FD8), em vez de empilhados dentro do card de oferta.
        val currencyContainer: LinearLayout? = if (currencySlots.isNotEmpty()) {
            LinearLayout(this).apply {
                tag = PRICE_ART_CURRENCY_TAG
                orientation = LinearLayout.HORIZONTAL
                currencySlots.forEachIndexed { index, slot ->
                    val boxColor = if (slot.field == "price_usd") Color.parseColor("#C8102E") else Color.parseColor("#1E4FD8")
                    val box = MaterialTextView(this@PlayerActivity).apply {
                        // Pedido do usuário (2026-11-17): +50% na fonte das caixas de Dólar/Peso,
                        // também na arte (mesmo bump aplicado em emphasizePriceSlot/populatePriceSlots).
                        val currencyBump = 1.5f
                        text = buildPriceSpannable(
                            value = slot.value,
                            textColor = Color.WHITE,
                            cifraoSp = 15f * currencyBump,
                            valueSp = 34f * currencyBump,
                            decimalsSp = 18f * currencyBump,
                            currencySymbol = slot.currencySymbol,
                        )
                        typeface = BrandTypography.bebasNeue(this@PlayerActivity)
                            ?: android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                        setPadding(dpToPx(15), dpToPx(15), dpToPx(15), dpToPx(15))
                        background = roundedBg(boxColor, radiusDp = 14f)
                        // Bug real (2026-11-17): sem isso, colocar a caixa dentro da coluna com o
                        // label acima (ver abaixo) fazia o texto quebrar letra por letra ("U"/"S"/"$"
                        // empilhados) — a LinearLayout vertical mede a largura do filho de forma
                        // diferente de quando ele é filho direto da LinearLayout horizontal.
                        isSingleLine = true
                        ellipsize = null
                    }
                    // Pedido do usuário (2026-11-17): label acima da caixa ("DÓLAR"/"PESO"), igual
                    // ao "PREÇO" acima do card principal — sem isso o cliente via só números
                    // coloridos, sem saber qual caixa era qual moeda.
                    val column = LinearLayout(this@PlayerActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            MaterialTextView(this@PlayerActivity).apply {
                                text = slot.label
                                // Bug real (2026-11-17, teste com imagem patrocinada real): usar
                                // `textColor` (calculado pro card principal, contra badgeColor) às
                                // vezes dava branco sobre uma região BRANCA da imagem patrocinada —
                                // label sumia (igual ao bug já visto na caixa de PREÇO). Pílula com
                                // o mesmo fundo da caixa abaixo garante contraste sempre, não importa
                                // o que tem atrás.
                                setTextColor(Color.WHITE)
                                setBackgroundColor(boxColor)
                                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                includeFontPadding = false
                            },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = dpToPx(6)
                            },
                        )
                        // Bug real (2026-11-17): LinearLayout VERTICAL dá MATCH_PARENT por padrão
                        // pra filho sem LayoutParams explícito (generateDefaultLayoutParams) — a
                        // caixa esticava pra largura do label curto "DÓLAR"/"PESO" e cortava quase
                        // tudo ("U" sozinho). WRAP_CONTENT explícito aqui é o que já funcionava
                        // antes de existir esta coluna.
                        addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    addView(
                        column,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (index > 0) marginStart = dpToPx(10)
                        },
                    )
                }
            }
        } else {
            null
        }
        currencyContainer?.let {
            parent.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        imgView.post {
            if (container.tag != PRICE_ART_BADGE_TAG || container.parent == null) return@post
            if (imgView.width <= 0 || imgView.height <= 0) return@post

            val hasCurrencyContainer = currencyContainer != null && currencyContainer.parent != null

            if (isVerticalArtLayout) {
                // Arte vertical sempre gerada em 1200x1920 (produtos-imgs: ARTE_LARGURA_VERTICAL/
                // ARTE_ALTURA_VERTICAL, ET45 em pé) — mesma ideia do par horizontal abaixo, só que
                // a caixa reservada pro preço tem posição FIXA (% de ARTE_ALTURA_VERTICAL a partir
                // do início do painel, não derivada do texto do nome — ver CAIXA_PRECO_* em
                // compor_arte_vertical) exatamente pra este cálculo poder replicar sem adivinhar
                // quantas linhas o nome ocupou no servidor.
                // Layout novo da arte vertical (referência do usuário, 05/11/2026): painel de cor no
                // TOPO e a caixa do preço no canto INFERIOR ESQUERDO. Estes 4 percentuais ESPELHAM
                // VERT_PRECO_X0/X1/Y0/Y1_PCT de produtos-imgs (app.py) — mantenha os dois lados iguais.
                // Artes verticais geradas antes dessa mudança têm a caixa em outro lugar e precisam
                // ser regeneradas pro preço cair certo.
                // Sem patrocínio: canvas fixo de sempre (produtos-imgs gera sempre 1200x1920) +
                // frações hardcoded, calibradas visualmente. Com patrocínio: o "canvas" é a
                // imagem de verdade (resolução arbitrária, enviada pelo Mupa Studio) e as frações
                // vêm da região desenhada lá — mesma matemática de escala/tarja, fonte diferente.
                val arteLarguraVertical = sponsorRegion?.let { imgView.drawable?.intrinsicWidth?.toFloat() } ?: 1200f
                val arteAlturaVertical = sponsorRegion?.let { imgView.drawable?.intrinsicHeight?.toFloat() } ?: 1920f
                val caixaX0Pct = sponsorRegion?.x0 ?: 0.05f
                val caixaX1Pct = sponsorRegion?.x1 ?: 0.55f
                val caixaY0Pct = sponsorRegion?.y0 ?: 0.80f
                val caixaY1Pct = sponsorRegion?.y1 ?: 0.94f

                val escalaFitCenter = minOf(imgView.width / arteLarguraVertical, imgView.height / arteAlturaVertical)
                val tarjaEsquerda = (imgView.width - arteLarguraVertical * escalaFitCenter) / 2f
                val tarjaSuperior = (imgView.height - arteAlturaVertical * escalaFitCenter) / 2f

                val caixaX0 = arteLarguraVertical * caixaX0Pct
                val caixaX1 = arteLarguraVertical * caixaX1Pct
                val caixaY0 = arteAlturaVertical * caixaY0Pct
                val caixaY1 = arteAlturaVertical * caixaY1Pct

                val caixaXCentro = imgView.left + tarjaEsquerda + (caixaX0 + caixaX1) / 2f * escalaFitCenter
                val caixaYCentro = imgView.top + tarjaSuperior + (caixaY0 + caixaY1) / 2f * escalaFitCenter

                container.x = caixaXCentro - container.width / 2f
                container.y = caixaYCentro - container.height / 2f
                if (hasCurrencyContainer) {
                    // Abaixo do cartão branco de preço, alinhado à esquerda com ele.
                    currencyContainer!!.x = container.x
                    currencyContainer.y = container.y + container.height + dpToPx(10)
                }
                return@post
            }

            // A arte horizontal é sempre gerada em 1280x800 (produtos-imgs: ARTE_LARGURA_HORIZONTAL /
            // ARTE_ALTURA_HORIZONTAL), com o texto (nome/descrição) desenhado a partir de uma
            // margem de 6% dessa largura (`margin = width * 0.06` em compor_texto_na_arte). O
            // ImageView usa FIT_CENTER (a arte nunca pode passar da borda da tela) — escala pelo
            // MENOR fator entre largura/altura da view e centraliza o resultado, sobrando uma
            // tarja (letterbox) de cada lado no eixo que não bateu a proporção. Usar só
            // "imgView.width * fração" pra posicionar o preço IGNORA essa tarja: o card ficaria
            // desalinhado com o texto desenhado na arte, que está relativo à imagem, não à view.
            if (sponsorRegion != null) {
                // Telas Patrocinadas: a região reservada já diz exatamente onde o badge cabe —
                // mesma matemática de escala/tarja da arte vertical (ver acima), centralizado na
                // região em vez do ancoramento por topo/altura-da-tela usado pra arte por
                // produto (que não tem uma área reservada conhecida de antemão).
                val naturalW = imgView.drawable?.intrinsicWidth?.toFloat() ?: 1280f
                val naturalH = imgView.drawable?.intrinsicHeight?.toFloat() ?: 800f
                val escala = minOf(imgView.width / naturalW, imgView.height / naturalH)
                val tarjaH = (imgView.width - naturalW * escala) / 2f
                val tarjaV = (imgView.height - naturalH * escala) / 2f
                val centroX = naturalW * (sponsorRegion.x0 + sponsorRegion.x1) / 2f
                val centroY = naturalH * (sponsorRegion.y0 + sponsorRegion.y1) / 2f
                val baseX = imgView.left + tarjaH + centroX * escala
                val baseY = imgView.top + tarjaV + centroY * escala

                // Consulta Patrocinada (pedido do usuário, 2026-11-26): foto como par com o
                // container de texto (mesmo visual de "foto à esquerda, texto à direita" de
                // antes, quando a foto era filha de `container`), os dois centralizados juntos
                // no centro da região reservada — sem foto (Telas Patrocinadas/arte por
                // produto), photoWidth=0 e isso reduz exatamente ao cálculo de sempre.
                val photoGap = if (sponsoredPhotoView != null) dpToPx(18).toFloat() else 0f
                val photoWidth = sponsoredPhotoView?.width?.toFloat() ?: 0f
                val pairWidth = photoWidth + photoGap + container.width
                val pairLeft = baseX - pairWidth / 2f

                sponsoredPhotoView?.let {
                    it.x = pairLeft
                    it.y = baseY - it.height / 2f
                }
                container.x = pairLeft + photoWidth + photoGap
                container.y = baseY - container.height / 2f
                if (hasCurrencyContainer) {
                    currencyContainer!!.x = container.x + container.width + dpToPx(16)
                    currencyContainer.y = container.y + (container.height - currencyContainer.height) / 2f
                }
                return@post
            }

            // A arte horizontal é sempre gerada em 1280x800 (produtos-imgs: ARTE_LARGURA_HORIZONTAL /
            // ARTE_ALTURA_HORIZONTAL), com o texto (nome/descrição) desenhado a partir de uma
            // margem de 6% dessa largura (`margin = width * 0.06` em compor_texto_na_arte). O
            // ImageView usa FIT_CENTER (a arte nunca pode passar da borda da tela) — escala pelo
            // MENOR fator entre largura/altura da view e centraliza o resultado, sobrando uma
            // tarja (letterbox) de cada lado no eixo que não bateu a proporção. Usar só
            // "imgView.width * fração" pra posicionar o preço IGNORA essa tarja: o card ficaria
            // desalinhado com o texto desenhado na arte, que está relativo à imagem, não à view.
            val arteLarguraOriginal = 1280f
            val arteAlturaOriginal = 800f
            val escalaFitCenter = minOf(imgView.width / arteLarguraOriginal, imgView.height / arteAlturaOriginal)
            val tarjaEsquerda = (imgView.width - arteLarguraOriginal * escalaFitCenter) / 2f
            val margemTextoNaArte = 0.06f
            container.x = imgView.left + tarjaEsquerda + arteLarguraOriginal * margemTextoNaArte * escalaFitCenter
            // Card de preço ancorado pelo TOPO na metade vertical da tela + 50px (pedido do
            // usuário, com referência visual) — não mais ancorado perto da base (92% da altura).
            // Usa imgView.height (a tela toda), não a altura da arte, porque é isso que "metade
            // da tela" significa; com a arte casando 1:1 com a tela (ver seção de tamanho fixo em
            // produtos-imgs CLAUDE.md) as duas métricas coincidem de qualquer forma.
            //
            // Ancorado pelo TOPO (não pelo centro) de propósito: com produtos de 3 faixas de
            // preço (normal + oferta + clube — ver ProductPriceSlot em PriceQueryEngine.kt) o
            // card fica mais alto, e centralizar pelo meio do card fazia o TOPO subir e invadir a
            // 2ª linha da descrição do produto acima. Ancorando pelo topo, o card sempre começa
            // no mesmo Y e cresce pra baixo conforme o número de faixas — nunca invade o texto
            // acima.
            //
            // Mas só ancorar pelo topo criou o problema oposto: com 3 faixas o card fica alto o
            // bastante pra ULTRAPASSAR a borda inferior da tela (a 3ª linha, "Oferta Clube K",
            // saía cortada por baixo — confirmado visualmente no teste com o EAN 7891515555917).
            // yMaximo garante que o card nunca passe da base, com uma margem de segurança —
            // desliza pra cima só o suficiente pra caber, sem nunca invadir o texto (o texto de
            // um único produto não chega nem perto da base da tela).
            val margemInferiorCard = dpToPx(24).toFloat()
            val yDesejado = imgView.top + imgView.height / 2f + 50f
            val yMaximo = imgView.top + imgView.height - margemInferiorCard - container.height
            container.y = minOf(yDesejado, yMaximo)

            if (hasCurrencyContainer) {
                // À direita do card de preço, centralizado verticalmente com ele.
                currencyContainer!!.x = container.x + container.width + dpToPx(16)
                currencyContainer.y = container.y + (container.height - currencyContainer.height) / 2f
            }
        }
    }

    /** Faz [panel] (o container da imagem/arte, ex.: priceRightPanel) ocupar a tela toda, sem
     * padding, por cima de qualquer outro elemento ([fullBleed] = true) — ou volta exatamente ao
     * estado (constraints + padding) que tinha antes desta função mexer nele, guardado em
     * [panel.tag] na 1ª chamada. Funciona em qualquer layout de preço sem precisar conhecer sua
     * estrutura original, já que só restaura o que ele mesmo já era. */
    private fun makeFullBleed(panel: View, fullBleed: Boolean) {
        val lp = panel.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (fullBleed) {
            if (panel.tag !is FullBleedOriginalState) {
                panel.tag = FullBleedOriginalState(
                    params = ConstraintLayout.LayoutParams(lp),
                    paddingLeft = panel.paddingLeft,
                    paddingTop = panel.paddingTop,
                    paddingRight = panel.paddingRight,
                    paddingBottom = panel.paddingBottom,
                )
            }
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToEnd = ConstraintLayout.LayoutParams.UNSET
            lp.endToStart = ConstraintLayout.LayoutParams.UNSET
            // Limpa também os vínculos do split VERTICAL (topToBottom/bottomToTop, usados pelos
            // layouts vertical_image_top/bottom via priceImageSplitGuide) — sem isso, um painel
            // que originalmente tinha `bottomToTop = priceImageSplitGuide` (ver XML) ficava com
            // DOIS vínculos de borda inferior ao mesmo tempo (o novo bottomToBottom=PARENT_ID +
            // o antigo bottomToTop=guideline, nunca desfeito), e o ConstraintLayout resolvia a
            // altura pelo vínculo mais restritivo (a guideline, a ~45% da tela) em vez de ocupar
            // a tela toda — bug real, pego testando a arte vertical em tela cheia no ET45: a
            // imagem só cobria até a guideline, com uma área preta (fora dos bounds da view)
            // ocupando o resto da tela abaixo.
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            panel.layoutParams = lp
            panel.setPadding(0, 0, 0, 0)
            panel.bringToFront()
        } else {
            val saved = panel.tag as? FullBleedOriginalState ?: return
            panel.layoutParams = ConstraintLayout.LayoutParams(saved.params)
            panel.setPadding(saved.paddingLeft, saved.paddingTop, saved.paddingRight, saved.paddingBottom)
            panel.tag = null
        }
    }

    private data class ArteBadgeRow(
        val label: String?,
        val value: Double,
        val symbol: String,
        val fromValue: Double?,
    )

    private data class FullBleedOriginalState(
        val params: ConstraintLayout.LayoutParams,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
    )

    /** Monta "R$ 10,49" com o cifrão pequeno, a parte inteira grande e os centavos reduzidos —
     * tamanhos absolutos em sp (não relativos ao texto ao redor), pra ficarem consistentes
     * entre badges de tamanhos de fonte diferentes (preço principal vs. secundário). */
    private fun buildPriceSpannable(
        value: Double,
        textColor: Int,
        cifraoSp: Float,
        valueSp: Float,
        decimalsSp: Float,
        currencySymbol: String = "R$",
    ): SpannableStringBuilder {
        val formatted = formatCurrency(value, currencySymbol) // ex.: "R$ 10,49" ou "US$ 2,99"
        val spannable = SpannableStringBuilder(formatted)
        spannable.setSpan(ForegroundColorSpan(textColor), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        fun sizePx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).toInt()

        val separatorIndex = formatted.indexOf(',')
        val firstSpaceIndex = formatted.indexOf(' ')
        if (separatorIndex > 0 && firstSpaceIndex in 0 until separatorIndex) {
            spannable.setSpan(
                AbsoluteSizeSpan(sizePx(cifraoSp)),
                0,
                firstSpaceIndex + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                AbsoluteSizeSpan(sizePx(valueSp)),
                firstSpaceIndex + 1,
                separatorIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                AbsoluteSizeSpan(sizePx(decimalsSp)),
                separatorIndex,
                formatted.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else {
            spannable.setSpan(AbsoluteSizeSpan(sizePx(valueSp)), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
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

    /**
     * Divide a descrição em 1ª linha (até 3 primeiras palavras, Poppins Bold via
     * [BrandTypography], nunca quebra em mais de 1 linha graças ao autosize) e 2ª linha
     * (resto da descrição, Poppins Thin, complementar). A regra de quais palavras vão em
     * cada linha é a mesma de sempre — só a tipografia/tamanho mudou.
     */
    private fun applyProductName(nameFirstLine: TextView, nameSecondLine: TextView?, raw: String) {
        val cleaned = raw.trim().replace("\\s+".toRegex(), " ")
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        val top = words.take(3).joinToString(" ").uppercase(Locale("pt", "BR"))
        val rest = words.drop(3).joinToString(" ").uppercase(Locale("pt", "BR"))

        val totalChars = cleaned.length
        val baseSizeSp =
            when {
                totalChars <= 14 -> 44f
                totalChars <= 20 -> 40f
                totalChars <= 28 -> 34f
                else -> 30f
            }

        nameFirstLine.text = top
        val maxSp = baseSizeSp * 0.70f
        val minSp = 16f
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            nameFirstLine,
            minSp.toInt(),
            maxSp.toInt().coerceAtLeast(minSp.toInt()),
            1,
            TypedValue.COMPLEX_UNIT_SP,
        )

        // A margem negativa entre a 1ª e a 2ª linha só faz sentido quando a 2ª linha existe de
        // verdade — sem ela, a margem negativa "corta" a própria 1ª linha (nada abaixo para
        // compensar o espaço puxado para cima).
        val hasSecondLine = nameSecondLine != null && rest.isNotBlank()
        (nameFirstLine.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = if (hasSecondLine) -dpToPx(20) else 0
            nameFirstLine.layoutParams = it
        }

        if (nameSecondLine != null) {
            if (hasSecondLine) {
                nameSecondLine.text = rest
                nameSecondLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSizeSp * 0.60f)
                nameSecondLine.visibility = View.VISIBLE
            } else {
                nameSecondLine.text = ""
                nameSecondLine.visibility = View.GONE
            }
        }
    }

    /** Frase amigável complementar (item 4), montada só a partir do que já está em [description]. */
    private fun applySmartCaption(captionView: TextView?, description: String?) {
        if (captionView == null) return
        val phrase = SmartDescription.parse(description).friendlyPhrase
        if (phrase.isNullOrBlank()) {
            captionView.text = ""
            captionView.visibility = View.GONE
        } else {
            captionView.text = phrase
            captionView.visibility = View.VISIBLE
        }
    }

    private fun splitOfferBadges(title: String): Pair<String, String?> {
        val t = title.trim().uppercase(Locale("pt", "BR"))
        if (t.isBlank()) return "OFERTA" to null
        val m = OfferIntelligence.LEVE_PAGUE_REGEX.find(t)
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
                        // Pedido do usuário (2026-10-09): +20% de velocidade (1.02 -> 1.224).
                        tts?.setSpeechRate(1.224f)
                        tts?.setPitch(1.0f)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                mainHandler.post { scheduleReturnToMedia(2000L, "tts_done") }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {}
                        })
                    }
                }
            }
    }

    /**
     * Fala o preço usando os rótulos que a própria integração devolveu (ex: Komprão, cuja API
     * já manda a label pronta em prices[].label, com {{a_partir_de_x}} já interpolado).
     *
     * Regra: preço normal → fala só o valor. Ofertas → fala o rótulo + o valor.
     * Ex: "7 reais e 19 centavos. A partir de 3 unidades, a unidade sai por 6 reais e 79 centavos."
     */
    private fun speakSlotsIfPossible(ean: String, slots: List<ProductPriceSlot>) {
        // Pedido/bug real (2026-11-17): NÃO gatear na flag do TTS nativo — ela só reflete se o
        // Android tem um motor de TTS instalado (ex: aparelhos TV box sem nenhum, como o K11
        // rk3576, onde TextToSpeech nunca inicializa). A voz Azure (ver speakWithVoiceSynth) não
        // depende disso; só o fallback nativo dela depende, e esse já é gateado dentro de
        // speakNative(). Gatear aqui também deixava a loja muda pra sempre nesses aparelhos
        // (nem o cache Azure chegava a ser alimentado, porque a função nunca era chamada).
        // Mesma dedup + ordem de populatePriceSlots (ver comentário lá) — o que é falado
        // precisa bater com o que está escrito na tela: oferta primeiro, sem repetir o preço
        // cheio como slot separado quando ele já aparece riscado dentro do box de oferta.
        val validos = slots.filter { it.value > 0.0 }
            .let { s ->
                val promoFromValues = s.filter { it.isPromo }.mapNotNull { it.fromValue }
                if (promoFromValues.isEmpty()) s else s.filterNot { !it.isPromo && !it.isClub && it.value in promoFromValues }
            }
            .sortedBy { if (it.isPromo) 0 else 1 }
        if (validos.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (ean == lastSpokenEan && now - lastSpokenAtMs < 3500L) return
        lastSpokenEan = ean
        lastSpokenAtMs = now

        val fala = StringBuilder()
        for (slot in validos) {
            // Conversão de moeda estrangeira (ver PriceQueryEngine.appendForeignCurrencySlots):
            // fala sempre com a unidade certa (dólar/dólares, peso/pesos). Bug real (2026-10-21):
            // caindo no ramo genérico abaixo, buildSpokenPrice (sem parâmetro de unidade) chamava
            // TUDO de "reais" — inclusive os valores já convertidos pra dólar/peso.
            if (slot.field == "price_usd" || slot.field == "price_ars") {
                val (singular, plural) = if (slot.field == "price_usd") "dólar" to "dólares" else "peso" to "pesos"
                fala.append("${OfferIntelligence.buildSpokenPrice(slot.value, singular, plural)}. ")
                continue
            }
            val valor = buildSpokenPrice(slot.value)
            if (slot.isPromo && slot.fromValue != null && slot.fromValue > slot.value) {
                // "Produto em oferta, de X por Y" (pedido do usuário, 2026-10-21) — mesma
                // estrutura de frase que OfferIntelligence já usa pra desconto percentual.
                fala.append("Produto em oferta! De ${buildSpokenPrice(slot.fromValue)} por $valor. ")
            } else if (slot.isPromo || slot.isClub) {
                val rotulo = slot.label.trim().trimEnd(':', '.').trim()
                if (rotulo.isNotBlank()) fala.append("$rotulo, ")
                fala.append("$valor. ")
            } else {
                // Preço normal: com só 1 tipo de preço na tela, fala só o valor (não precisa
                // dizer "Preço Normal" se não há outro preço pra confundir). Pedido do usuário
                // (2026-10-23): com 2+ tipos (ex.: Oferta + Preço Normal + Oferta Clube K),
                // precede cada um pelo rótulo também aqui — sem isso, só os valores de
                // promo/clube eram anunciados com nome, e o normal ficava um número solto no
                // meio da fala, sem dar pra saber qual preço é qual.
                if (validos.size > 1) {
                    val rotulo = slot.label.trim().trimEnd(':', '.').trim()
                    if (rotulo.isNotBlank()) fala.append("$rotulo, ")
                }
                fala.append("$valor. ")
            }
        }

        val texto = fala.toString().trim()
        if (texto.isBlank()) return
        Log.d("MPlayerPrice", "tts_slots_text ean=$ean texto=$texto")
        speakWithVoiceSynth(texto)
    }

    /**
     * Fala a melhor mensagem para o produto (item 6): a mesma [OfferIntelligence] usada para o
     * selo/explicação decide o texto, então nunca diverge do que está escrito na tela.
     */
    private fun speakPriceIfPossible(product: PriceProduct) {
        // Ver nota em speakSlotsIfPossible: não gatear em ttsReady aqui.
        val now = SystemClock.elapsedRealtime()
        if (product.ean == lastSpokenEan && now - lastSpokenAtMs < 3500L) return

        val basePhrase = OfferIntelligence.analyze(product).ttsPhrase
        if (basePhrase.isBlank()) return

        // Conversão de moeda estrangeira (ver PriceQueryEngine.appendForeignCurrencySlots): fala
        // os valores convertidos depois do preço em reais, cada um com a unidade certa (dólar/
        // dólares, peso/pesos) — independente do OfferIntelligence, que é só reais.
        val currencyPhrase = product.priceSlots
            ?.filter { it.field == "price_usd" || it.field == "price_ars" }
            ?.joinToString(separator = " ") { slot ->
                val (singular, plural) = if (slot.field == "price_usd") "dólar" to "dólares" else "peso" to "pesos"
                "e ${OfferIntelligence.buildSpokenPrice(slot.value, singular, plural)}."
            }
            .orEmpty()
        val textToSpeak = if (currencyPhrase.isBlank()) basePhrase else "$basePhrase $currencyPhrase"

        lastSpokenEan = product.ean
        lastSpokenAtMs = now

        speakWithVoiceSynth(textToSpeak)
    }

    private fun speakNotFoundIfPossible(ean: String) {
        // Ver nota em speakSlotsIfPossible: não gatear em ttsReady aqui.
        val normalized = ean.trim()
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        if (normalized == lastNotFoundEan && now - lastNotFoundAtMs < 2500L) return
        lastNotFoundEan = normalized
        lastNotFoundAtMs = now

        speakWithVoiceSynth("Produto não encontrado!")
    }

    /**
     * Fala [texto] pela voz Azure cacheada (ver [PriceVoiceSynth]); se não
     * conseguir por qualquer motivo (sem rede, cache frio + Edge Function
     * fora do ar), cai pro TextToSpeech nativo do Android — a loja nunca
     * fica muda, só soa mais robótica nesse caso raro.
     */
    private fun speakWithVoiceSynth(texto: String) {
        priceVoiceSynth.speak(texto, onFallback = { speakNative(texto) })
    }

    private fun speakNative(texto: String) {
        // Só este caminho (voz Azure indisponível: cache frio + rede/Edge Function fora do ar)
        // realmente depende do motor de TTS nativo do Android — aparelhos sem nenhum (ex: TV
        // boxes genéricas) ficam só sem o fallback aqui, não sem a voz Azure inteira.
        if (!ttsReady) return
        val utteranceId = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= 21) {
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    private fun buildSpokenPrice(value: Double): String = OfferIntelligence.buildSpokenPrice(value)

    private fun formatCurrency(value: Double, symbol: String = "R$"): String {
        return String.format(Locale("pt", "BR"), "%s %.2f", symbol, value)
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
        // syncOverlay nunca mais fica visível (ver setSyncOverlayVisible) — só o pill (canto
        // superior direito) é mostrado, com a mensagem principal (status).
        binding.syncPillText.text = status
        if (progressPercent != null && progressPercent in 0..100) {
            binding.syncPillProgressBar.isIndeterminate = false
            binding.syncPillProgressBar.progress = progressPercent
        } else {
            binding.syncPillProgressBar.isIndeterminate = true
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
            "universal" -> R.layout.price_check_universal
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

        // Spinner de carregamento sobre a foto do produto, enquanto a imagem real (ImagemUrl
        // da API, baixada e cacheada em segundo plano por PriceQueryEngine.preloadProductImageAndTheme)
        // ainda não chegou — pedido do usuário (2026-11-13) pra não deixar a área da foto vazia/
        // com o placeholder genérico enquanto baixa. `container` é recriado do zero
        // (removeAllViews + inflate) a cada produto escaneado, então criar e adicionar aqui de
        // novo a cada chamada não acumula views órfãs. Usa tag em vez de um R.id próprio — mais
        // simples que declarar um id novo só pra isso. Posicionado via translationX/Y depois do
        // layout, em vez de LayoutParams do tipo do pai, porque os layouts split/universal/
        // backdrop/etc. usam ViewGroups diferentes (FrameLayout, ConstraintLayout...) e isso
        // funciona igual em qualquer um deles.
        val imageParent = imgView?.parent as? ViewGroup
        if (imageParent != null) {
            val spinner = ProgressBar(container.context).apply {
                tag = "priceImageLoadingSpinner"
                isIndeterminate = true
                visibility = View.GONE
            }
            imageParent.addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            imgView.doOnLayout { iv ->
                spinner.translationX = iv.x + iv.width / 2f - spinner.width / 2f
                spinner.translationY = iv.y + iv.height / 2f - spinner.height / 2f
            }
        }
    }

    /**
     * Anima a "contagem" do valor: os dígitos sobem de 0 até o preço final em ~600ms com
     * desaceleração. [slotIndex] escalona o início para os boxes contarem em sequência.
     */
    private fun animatePriceCount(
        integerTv: TextView,
        decimalTv: TextView,
        targetValue: Double,
        slotIndex: Int,
    ) {
        integerTv.animate().cancel()
        (integerTv.getTag(R.id.slotIntegerPrice) as? ValueAnimator)?.cancel()

        // Estado inicial: zerado
        integerTv.text = "0"
        decimalTv.text = ",00"

        val animator = ValueAnimator.ofFloat(0f, targetValue.toFloat()).apply {
            duration = 1000L
            startDelay = (160 + slotIndex * 90).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val current = (va.animatedValue as Float).toDouble()
                val formatted = String.format(Locale.US, "%.2f", current)
                val parts = formatted.split(".")
                integerTv.text = parts[0]
                decimalTv.text = ",${parts[1]}"
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Garante o valor exato no final (evita erro de ponto flutuante)
                    val formatted = String.format(Locale.US, "%.2f", targetValue)
                    val parts = formatted.split(".")
                    integerTv.text = parts[0]
                    decimalTv.text = ",${parts[1]}"
                    // Pequeno "pulso" ao travar no valor final
                    integerTv.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90)
                        .withEndAction {
                            integerTv.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                        }.start()
                }
            })
        }
        integerTv.setTag(R.id.slotIntegerPrice, animator)
        animator.start()
    }

    /** Varre uma faixa de luz diagonal, suave e elegante: vai e volta com easing cadenciado. */
    private fun playShineSweep() {
        val shine = binding.priceShine
        shine.animate().cancel()
        val parentWidth = (shine.parent as? View)?.width ?: binding.priceOverlay.width
        if (parentWidth <= 0) return

        val startX = -shine.width.toFloat() - 140f
        val endX = parentWidth.toFloat() + 140f

        // Easing suave estilo "ease-in-out" (cubic-bezier 0.4, 0.0, 0.2, 1)
        val smooth = androidx.core.view.animation.PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)

        shine.translationX = startX
        shine.alpha = 0f
        shine.visibility = View.VISIBLE
        shine.animate()
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Ida: esquerda → direita (1300ms, bem cadenciada)
                shine.animate()
                    .translationX(endX)
                    .setDuration(1300)
                    .setInterpolator(smooth)
                    .withEndAction {
                        // Volta: direita → esquerda (1300ms)
                        shine.animate()
                            .translationX(startX)
                            .setDuration(1300)
                            .setInterpolator(smooth)
                            .withEndAction {
                                shine.animate().alpha(0f).setDuration(320)
                                    .withEndAction { shine.visibility = View.GONE }
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
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
            val primarySlot = productSlots.firstOrNull { !it.isPromo && !it.isClub && (it.field == "price" || it.label.contains("NORMAL", true)) }
            val basePriceForDeduplication = primarySlot?.value ?: product.price
            
            val positiveSlots = productSlots.filter { it.value > 0.0 }
            val dedupedSlots = positiveSlots.filter { slot ->
                if (basePriceForDeduplication != null && slot.value == basePriceForDeduplication) {
                    val isSecondary = slot.isPromo || slot.isClub ||
                                     slot.field == "price_wholesale" ||
                                     slot.field == "priceWholesale" ||
                                     slot.field == "price_club" ||
                                     slot.field == "priceClub" ||
                                     slot.field == "price_promotional" ||
                                     slot.field == "pricePromotional"
                    !isSecondary
                } else {
                    true
                }
            }
            // Nunca deixar a deduplicação esconder TODOS os preços: se a API só devolveu um
            // slot e ele veio marcado como "secundário" (ex: PRECO_PDV sem PRECO_NORMAL — ver
            // parseKompraoResponse), ele é o único preço que existe, não um duplicado a
            // esconder. Nesse caso cai pra lista sem dedup em vez de ficar vazia (bug real:
            // tela sem nenhum preço enquanto o TTS, que não passa por essa dedup, falava
            // normalmente).
            val validSlots = dedupedSlots.ifEmpty { positiveSlots }
                .let { slots ->
                    // Quando há oferta (Preco2 do TCServer < Preco1), o preço cheio já aparece
                    // riscado ("De: X") dentro do próprio box de oferta — mostrar também como box
                    // separado é redundante e ocupa espaço à toa (achado real, 2026-10-21, EAN
                    // 7891010980061). Remove o box "preço normal" cujo valor é o "De:" da oferta.
                    val promoFromValues = slots.filter { it.isPromo }.mapNotNull { it.fromValue }
                    if (promoFromValues.isEmpty()) {
                        slots
                    } else {
                        slots.filterNot { !it.isPromo && !it.isClub && it.value in promoFromValues }
                    }
                }
                // Produto em oferta tem prioridade visual: aparece primeiro na tela.
                .sortedBy { if (it.isPromo) 0 else 1 }
            // Slots de conversão de moeda estrangeira (ver PriceQueryEngine.queryTcServer, moeda_dolar/
            // moeda_peso) não competem pelo destaque "melhor preço" — o valor numérico é menor só por
            // causa da unidade (dólar/peso), não porque é uma oferta melhor. Excluídos do cálculo de
            // bestValue para não ficarem destacados como se fossem a opção mais vantajosa.
            fun isCurrencyConversionSlot(s: ProductPriceSlot) = s.field == "price_usd" || s.field == "price_ars"
            val bestValue = validSlots.filterNot { isCurrencyConversionSlot(it) }.minOfOrNull { it.value }
            val compact = validSlots.size >= 3
            lastRenderedPriceSlotCount = validSlots.size.coerceAtLeast(1)
            // Pedido do usuário (2026-11-17): preço em real continua na coluna principal; as
            // conversões de moeda estrangeira (Dólar/Peso) vão pra uma coluna à direita, lado a
            // lado — não mais empilhadas embaixo do preço em real. Ver addPriceSlotColumns.
            val mainViews = ArrayList<Pair<View, Double>>()
            val currencyViews = ArrayList<Pair<View, Double>>()
            for (slot in validSlots) {
                val itemView = inflater.inflate(R.layout.item_price_slot, container, false)

                val labelTextView = itemView.findViewById<TextView>(R.id.slotLabel)
                val integerTextView = itemView.findViewById<TextView>(R.id.slotIntegerPrice)
                val decimalTextView = itemView.findViewById<TextView>(R.id.slotDecimalPrice)
                val fromPriceTextView = itemView.findViewById<TextView>(R.id.slotFromPrice)
                val currencyTextView = itemView.findViewById<TextView>(R.id.slotCurrency)

                labelTextView.text = slot.label
                currencyTextView.text = slot.currencySymbol
                // Bug real (2026-11-17): a fonte Bebas Neue só era aplicada DEPOIS de montar/medir
                // as colunas (BrandTypography.applyBrandTypography, chamado mais tarde) — como ela
                // é bem mais larga que a fonte default, trocar o typeface DEPOIS forçava um
                // re-layout tardio que estourava a coluna da esquerda, empurrando a de moeda pra
                // fora/zerando sua largura. Aplica aqui, ANTES de medir, pra já nascer com a
                // largura final certa.
                BrandTypography.bebasNeue(this)?.let {
                    currencyTextView.typeface = it
                    integerTextView.typeface = it
                    decimalTextView.typeface = it
                }

                val isBest = !isCurrencyConversionSlot(slot) && bestValue != null && slot.value == bestValue
                styleSlotBox(
                    itemView, labelTextView, currencyTextView, integerTextView, decimalTextView,
                    isBest = isBest, isClub = slot.isClub, isPromo = slot.isPromo, accentColor = accentColor,
                    isUsd = slot.field == "price_usd", isArs = slot.field == "price_ars",
                )

                animatePriceCount(integerTextView, decimalTextView, slot.value, container.childCount)

                emphasizePriceSlot(
                    labelTextView, currencyTextView, integerTextView, decimalTextView,
                    isBest = isBest,
                    compact = compact,
                    value = slot.value,
                    // Conversão de moeda (ver nota acima) não é "oferta pior" — usa o mesmo
                    // tamanho de destaque do preço principal, só sem a cor de "melhor preço".
                    isCurrencyConversion = isCurrencyConversionSlot(slot),
                )
                applyCompactSpacing(itemView, compact)

                // "de" riscado dentro do próprio box (ex: De/Por, promoção)
                val fromV = slot.fromValue
                if (fromV != null && fromV > slot.value) {
                    fromPriceTextView.text = "De: ${formatCurrency(fromV)}"
                    fromPriceTextView.paintFlags = fromPriceTextView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    fromPriceTextView.visibility = View.VISIBLE
                } else {
                    fromPriceTextView.visibility = View.GONE
                }

                if (isCurrencyConversionSlot(slot)) currencyViews += itemView to slot.value else mainViews += itemView to slot.value
            }
            if (currencyViews.isEmpty()) {
                mainViews.forEach { (view, _) -> container.addView(view) }
            } else {
                addPriceSlotColumns(container, mainViews, currencyViews)
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

            animatePriceCount(integerTextView, decimalTextView, priceValue, container.childCount)

            emphasizePriceSlot(
                labelTextView, currencyTextView, integerTextView, decimalTextView,
                isBest = bestValue != null && priceValue == bestValue,
                compact = compact,
                value = priceValue,
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
     * Separa o(s) preço(s) principal(is) (coluna da esquerda, como já era) das conversões de
     * moeda estrangeira — Dólar/Peso — numa coluna à direita, lado a lado dentro do mesmo
     * price_container. Só chamada quando existe pelo menos uma conversão (ver populatePriceSlots);
     * sem moeda estrangeira o comportamento continua idêntico ao de antes (tudo empilhado).
     */
    private fun addPriceSlotColumns(container: LinearLayout, mainViews: List<Pair<View, Double>>, currencyViews: List<Pair<View, Double>>) {
        val row = LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val leftColumn = LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val rightColumn = LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(10)
            }
        }
        // Bug real (2026-11-17): item_price_slot.xml tem o root "match_parent" — e o TCServer
        // (único caminho que gera conversão de moeda) sempre usa price_check_multi_price.xml,
        // cujo price_container vive dentro de um ScrollView. Só trocar o width pra WRAP_CONTENT
        // não bastou: o ScrollView remede o conteúdo numa passada depois, e o "match_parent"
        // original cascateava de novo, estourando a coluna esquerda e zerando a direita — sem
        // nenhum erro, só colapsando visualmente (confirmado com uiautomator: os dois rótulos
        // caíam na mesma posição X). Mede a largura NATURAL de cada caixa manualmente e fixa um
        // valor em pixels (não uma palavra-chave) — assim nenhuma remedição posterior tem
        // ambiguidade pra resolver.
        // Bug real (2026-11-17, Novex/Colágeno): o valor exibido quando esta função mede a caixa
        // ainda é "0,00" — animatePriceCount só põe o texto FINAL em frames futuros do
        // ValueAnimator (ainda não disparados nesta mesma chamada síncrona). Peso/Dólar têm bem
        // mais dígitos que o "0,00" inicial (ex.: conversão de R$20 vira "15880,88" em peso), e a
        // largura fixada com o texto errado cortava o valor real assim que a animação chegava lá
        // ("$3..."). Troca o texto pro valor final só pra medir, mede, e devolve pro estado que a
        // animação espera encontrar — tudo síncrono, sem nenhum frame desenhado entre o troca-mede-
        // devolve, então não pisca.
        // Peso é a conversão com mais dígitos de longe (cotação alta faz R$20 virar "15880,88") —
        // o "fit" de emphasizePriceSlot já encolhe por quantidade de dígitos do PRÓPRIO valor,
        // mas foi calibrado antes de existir conversão de moeda, pra no máximo ~4 dígitos. Com 5+
        // dígitos a caixa ainda estourava a tela (achado real, 2026-11-17, Colágeno: "15880,88"
        // saía da borda direita). Trava final por largura disponível: mede, e se passar do teto,
        // encolhe a fonte na proporção exata que falta e remede — robusto a qualquer cotação,
        // não só aos dígitos de hoje.
        val maxBoxWidthPx = dpToPx(300)
        // Achado real (2026-11-17, Colágeno): view.measure(UNSPECIFIED) nesta árvore sempre
        // devolvia um valor perto do minWidth=150dp da caixa (item_price_slot.xml), IGNORANDO o
        // texto real já aplicado nos TextViews — confirmado comparando com
        // TextView.paint.measureText() dos mesmos textos, que SEMPRE refletiu a diferença real
        // entre "107,99" e "15880,88" (o view.measure() não). Por isso a largura "fixada" não
        // tinha nenhuma relação com o conteúdo, e caixas com muitos dígitos (peso, cotação alta)
        // estouravam a tela mesmo com o teto em vigor. Mede via Paint.measureText direto — não
        // depende do pipeline de layout do Android, só do texto e do tamanho de fonte atual.
        fun fixMeasuredWidth(view: View, finalValue: Double) {
            val unitLabelTv = view.findViewById<TextView>(R.id.slotUnitLabel)
            val currencyTv = view.findViewById<TextView>(R.id.slotCurrency)
            val integerTv = view.findViewById<TextView>(R.id.slotIntegerPrice)
            val decimalTv = view.findViewById<TextView>(R.id.slotDecimalPrice)

            val formatted = String.format(Locale.US, "%.2f", finalValue)
            val parts = formatted.split(".")
            val integerText = parts[0]
            val decimalText = ",${parts[1]}"

            fun naturalWidthPx(): Int {
                val unitLabelW = unitLabelTv?.text?.toString()?.split("\n")
                    ?.maxOfOrNull { unitLabelTv.paint.measureText(it) } ?: 0f
                val currencyW = currencyTv?.paint?.measureText(currencyTv.text?.toString().orEmpty()) ?: 0f
                val integerW = integerTv?.paint?.measureText(integerText) ?: 0f
                val decimalW = decimalTv?.paint?.measureText(decimalText) ?: 0f
                // paddingStart+paddingEnd (16dp+16dp) e layout_margin (4dp*2 lados) do slotPriceBox,
                // + marginEnd do unitLabel (8dp) e da moeda (3dp) — ver item_price_slot.xml.
                val fixedSpacing = dpToPx(32) + dpToPx(8) + dpToPx(8) + dpToPx(3)
                return (unitLabelW + currencyW + integerW + decimalW).toInt() + fixedSpacing
            }

            var width = naturalWidthPx()
            if (width > maxBoxWidthPx) {
                val scale = maxBoxWidthPx.toFloat() / width.toFloat()
                // getTextSize() devolve em PX; o setter de 1 argumento interpreta em SP — misturar
                // os dois reescala errado (bug clássico do Android). Usa COMPLEX_UNIT_PX nos dois
                // lados pra não ter conversão de unidade nenhuma no meio.
                listOfNotNull(integerTv, decimalTv, currencyTv).forEach {
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * scale)
                }
                width = naturalWidthPx()
            }
            val lp = view.layoutParams as? ViewGroup.LayoutParams
                ?: ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.width = width
            view.layoutParams = lp
        }
        mainViews.forEach { (view, value) -> fixMeasuredWidth(view, value); leftColumn.addView(view) }
        currencyViews.forEach { (view, value) -> fixMeasuredWidth(view, value); rightColumn.addView(view) }
        row.addView(leftColumn)
        row.addView(rightColumn)
        container.addView(row)
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
        isUsd: Boolean = false,
        isArs: Boolean = false,
    ) {
        val boxColor = when {
            // Pedido do usuário (2026-11-17): Dólar sempre vermelho, Peso sempre azul — cor fixa,
            // igual ao Mupa Studio (sample.ts RED/BLUE) — não a cor de destaque do produto, que
            // varia e pode até coincidir com o fundo do painel (ficou "sem caixa" num teste real
            // com Oral-B). O preço em real (isBest) continua com a cor extraída da imagem.
            isUsd -> Color.parseColor("#C8102E")
            isArs -> Color.parseColor("#1E4FD8")
            isClub -> Color.parseColor("#10b981")
            isPromo -> Color.parseColor("#ef4444")
            isBest -> accentColor
            else -> Color.parseColor("#1E6FC4")
        }
        val textColor = idealTextColor(boxColor)

        itemView.findViewById<View>(R.id.slotPriceBox)?.background = roundedBg(boxColor)
        integer.setTextColor(textColor)
        decimal.setTextColor(textColor)
        currency?.setTextColor(textColor)
        // Label lateral "Preço por un." e info à direita ficam DENTRO da caixa → cor de contraste
        itemView.findViewById<TextView>(R.id.slotUnitLabel)?.setTextColor(adjustAlpha(textColor, 0.85f))
        itemView.findViewById<TextView>(R.id.slotFromPrice)?.setTextColor(adjustAlpha(textColor, 0.85f))
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
        value: Double = 0.0,
        isCurrencyConversion: Boolean = false,
    ) {
        // Preços bem maiores para leitura a distância (item 1). O melhor preço é o
        // maior destaque da tela; com 3+ tipos, reduz um pouco (compact) pra caber sem rolar.
        // Tamanhos do preço aumentados +25% para leitura a distância.
        // O fator abaixo encolhe a fonte conforme a parte inteira cresce (ex.: "10,29",
        // "199,90") para o valor caber numa única linha e manter a harmonia do box.
        val digits = kotlin.math.abs(value.toInt()).toString().length
        val fit = when {
            digits <= 1 -> 1.0f
            digits == 2 -> 0.82f
            digits == 3 -> 0.66f
            else -> 0.55f
        }
        // Pedido do usuário (2026-10-23): com 3+ tipos (compact), o valor do preço ainda
        // estourava a caixa — reduz mais 30% (em cima do tamanho "compact" já existente),
        // em todos os slots (melhor preço e secundários), não só na fonte "fit" por dígitos.
        val compactShrink = if (compact) 0.7f else 1.0f
        // Pedido do usuário (2026-11-13): +30% em cima do tamanho já existente (que já tinha um
        // +25% de 2026-10-23 pra leitura a distância — este é cumulativo, não substitui aquele).
        // Pedido do usuário (2026-11-17): +20% de novo em cima disso tudo, junto da troca de
        // fonte pra Bebas Neue (ver BrandTypography) — "sizeBump2" é só mais uma camada, mesmo
        // princípio das anteriores.
        val emphasisBump = 1.3f
        val sizeBump2 = 1.2f
        // Pedido do usuário (2026-11-17): +50% na fonte das caixas de Dólar/Peso — e, no mesmo
        // pedido, também no preço principal (isBest) deste layout, pra manter a proporção visual
        // entre as 3 caixas (sem isso, PREÇO ficava menor que Dólar/Peso por comparação).
        val currencyBump = if (isBest || isCurrencyConversion) 1.5f else 1.0f
        if (isBest || isCurrencyConversion) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 18f)
            label.alpha = 1f
            currency?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 33f else 43f) * fit * compactShrink * emphasisBump * sizeBump2 * currencyBump)
            integer.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 83f else 110f) * fit * compactShrink * emphasisBump * sizeBump2 * currencyBump)
            decimal.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 45f else 58f) * fit * compactShrink * emphasisBump * sizeBump2 * currencyBump)
        } else {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 14f)
            label.alpha = 0.85f
            currency?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 23f else 28f) * fit * compactShrink * emphasisBump * sizeBump2)
            integer.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 50f else 65f) * fit * compactShrink * emphasisBump * sizeBump2)
            decimal.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (compact) 28f else 35f) * fit * compactShrink * emphasisBump * sizeBump2)
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
        box?.setPadding(dpToPx(14), dpToPx(2), dpToPx(14), dpToPx(2))
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



    /**
     * Selo/explicação de oferta (itens 3 e 5): [OfferIntelligence] decide qual é a única
     * vantagem a destacar. "LEVE X PAGUE Y" mantém os 2 chips tradicionais; qualquer outro
     * tipo de vantagem usa só o chip esquerdo (nunca dois selos competindo).
     */
    private fun renderOffer(product: PriceProduct, offerContainer: View?, offerText: TextView?) {
        val badgesRow = binding.priceResultRoot.findViewById<View>(R.id.priceBadgesRow)
        val leftBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceLeftBadgeText)
        val rightBadge = binding.priceResultRoot.findViewById<TextView>(R.id.priceRightBadgeText)
        val secondUnitContainer = binding.priceResultRoot.findViewById<View>(R.id.priceSecondUnitContainer)
        val secondUnitValue = binding.priceResultRoot.findViewById<TextView>(R.id.priceSecondUnitValue)

        val offer = product.offer
        if (offer == null || !offer.enabled) {
            badgesRow?.visibility = View.GONE
            leftBadge?.visibility = View.GONE
            rightBadge?.visibility = View.GONE
            offerContainer?.visibility = View.GONE
            secondUnitContainer?.visibility = View.GONE
            return
        }

        val insight = OfferIntelligence.analyze(product)
        val offerRed = Color.parseColor("#ef4444")
        badgesRow?.visibility = View.VISIBLE

        if (insight.type == AdvantageType.LEVE_PAGUE) {
            val (badgeA, badgeB) = splitOfferBadges(offer.title?.trim().orEmpty())
            if (badgeA.isNotBlank()) {
                leftBadge?.text = badgeA
                leftBadge?.setTextColor(Color.WHITE)
                leftBadge?.background = roundedBg(offerRed, radiusDp = 8f)
                leftBadge?.visibility = View.VISIBLE
            } else {
                leftBadge?.visibility = View.GONE
            }
            if (!badgeB.isNullOrBlank()) {
                rightBadge?.text = badgeB
                rightBadge?.setTextColor(Color.WHITE)
                rightBadge?.background = roundedBg(offerRed, radiusDp = 8f)
                rightBadge?.visibility = View.VISIBLE
            } else {
                rightBadge?.visibility = View.GONE
            }
        } else if (insight.badgeText.isNotBlank()) {
            leftBadge?.text = "${insight.badgeEmoji} ${insight.badgeText}".trim()
            leftBadge?.setTextColor(Color.WHITE)
            leftBadge?.background = roundedBg(offerRed, radiusDp = 8f)
            leftBadge?.visibility = View.VISIBLE
            rightBadge?.visibility = View.GONE
        } else {
            leftBadge?.visibility = View.GONE
            rightBadge?.visibility = View.GONE
        }

        val explanation = insight.shortExplanation
        if (offerText != null && !explanation.isNullOrBlank()) {
            offerText.text = explanation
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

        // Pedido do usuário (2026-10-23): esses layouts "específicos" (price_check_*) são de
        // antes do priceSlots[] existir e só olhavam os campos escalares do produto
        // (product.price/pricePromotional/priceClub/...) — a integração Komprão nem sempre
        // preenche esses campos (o detalhe completo vem em priceSlots[]). Com o `hasArt` certo
        // (não mascara mais isso com o badge da arte, que já lia priceSlots[] direto), só a
        // Oferta aparecia aqui porque era o único campo escalar preenchido. Deriva de
        // priceSlots[] quando existir, caindo pros campos escalares só se não tiver.
        val slotNormal = product.priceSlots?.firstOrNull { !it.isPromo && !it.isClub && !it.field.contains("wholesale", true) && !it.field.contains("atacado", true) }
        val slotPromo = product.priceSlots?.firstOrNull { it.isPromo }
        val slotClub = product.priceSlots?.firstOrNull { it.isClub }
        val slotWholesale = product.priceSlots?.firstOrNull { it.field.contains("wholesale", true) || it.field.contains("atacado", true) }

        val normalVal = slotNormal?.value ?: product.price
        val promoVal = slotPromo?.value ?: product.pricePromotional
        val clubVal = slotClub?.value ?: (product.priceClub ?: product.clubPrice)
        val cardVal = product.cardPrice
        val wholesaleVal = slotWholesale?.value ?: product.priceWholesale
        val fromVal = slotPromo?.fromValue ?: (product.priceFrom ?: product.originalPrice)

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
        const val ZAFFARI_TEST_MODE = false
        const val MAX_CONSECUTIVE_SCAN_FAILURES = 5
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
