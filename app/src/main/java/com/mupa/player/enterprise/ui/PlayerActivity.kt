package com.mupa.player.enterprise.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.PixelCopy
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.mupa.player.enterprise.bridge.AndroidBridge
import com.mupa.player.enterprise.databinding.ActivityPlayerBinding
import com.mupa.player.enterprise.kiosk.KioskManager
import com.mupa.player.enterprise.kiosk.DeviceOwnerPolicyManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.MupaCommandCenter
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.managers.WakeLockManager
import com.mupa.player.enterprise.services.CommandAck
import com.mupa.player.enterprise.services.DeviceCommand
import com.mupa.player.enterprise.services.FirebaseRealtimeCommandService
import com.mupa.player.enterprise.services.FirebaseRealtimePolicyService
import com.mupa.player.enterprise.services.MupaLocalApiService
import com.mupa.player.enterprise.services.MupaKeepAliveService
import com.mupa.player.enterprise.services.ScreenRecordService
import com.mupa.player.enterprise.utils.DeviceInfoProvider
import com.mupa.player.enterprise.webview.CustomWebChromeClient
import com.mupa.player.enterprise.webview.MupaWebViewClient
import com.mupa.player.enterprise.webview.WebViewConfigurator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private lateinit var settingsManager: SettingsManager
    private lateinit var kioskManager: KioskManager
    private lateinit var wakeLockManager: WakeLockManager
    private var policyReceiver: BroadcastReceiver? = null

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var lastConnectedAtElapsed = SystemClock.elapsedRealtime()

    private var firebaseCommandService: FirebaseRealtimeCommandService? = null
    private var deviceIdForCommands: String = ""
    private var safePlayerUrl: String? = null

    private var devTapCount = 0
    private var devTapStartMs = 0L
    private var backUnlockTapCount = 0
    private var backUnlockStartMs = 0L
    private var unlockDialogShowing = false

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingWebPermissionResources: Array<String>? = null
    private var pendingAndroidPermissions: Array<String> = emptyArray()

    private val unlockHandler = Handler(Looper.getMainLooper())
    private var unlockRunnable: Runnable? = null
    private var secretTwoFingerDown = false
    private var secretStartX = 0f
    private var secretStartY = 0f
    private var secretRunnable: Runnable? = null
    private val secretHoldMs = 3000L
    private var navigatedToRegistration = false
    private var noPlaylistActive = false
    private var overlayMode: OverlayMode = OverlayMode.None
    private var lastProjectionResultCode: Int? = null
    private var lastProjectionResultData: Intent? = null
    private var screenRecordRequested = false
    private var screenRecordRunning = false
    private var timelapseRunning = false

    private val webRuntimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val webRequest = pendingWebPermissionRequest
            val webResources = pendingWebPermissionResources
            val requiredAndroidPermissions = pendingAndroidPermissions

            pendingWebPermissionRequest = null
            pendingWebPermissionResources = null
            pendingAndroidPermissions = emptyArray()

            if (webRequest == null || webResources == null) return@registerForActivityResult

            val allGranted = requiredAndroidPermissions.all { perm ->
                (result[perm] == true) || isAndroidPermissionGranted(perm)
            }
            if (allGranted) {
                webRequest.grant(webResources)
            } else {
                webRequest.deny()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            screenRecordRequested = false
            applyLockTaskForScreenCapture(allowSystemUi = false)
            if (result.resultCode != RESULT_OK || result.data == null) {
                return@registerForActivityResult
            }
            lastProjectionResultCode = result.resultCode
            lastProjectionResultData = result.data
            startScreenRecord30s()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extraStartUrl = intent.getStringExtra(EXTRA_START_URL)?.trim().orEmpty()
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS_PANEL, false)

        settingsManager = SettingsManager(applicationContext)
        kioskManager = KioskManager(this, settingsManager)
        wakeLockManager = WakeLockManager(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"

        runCatching { startForegroundKeepAlive() }.onFailure {
            AndroidBridge.showToast(this, "Erro ao iniciar keep-alive")
        }
        runCatching { startService(Intent(this, MupaLocalApiService::class.java)) }.onFailure {
            AndroidBridge.showToast(this, "Erro ao iniciar API local")
        }
        runCatching { kioskManager.applyNow() }.onFailure {
            AndroidBridge.showToast(this, "Erro ao aplicar kiosk")
        }

        binding.hiddenDevTapArea.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - devTapStartMs > 2500) {
                devTapStartMs = now
                devTapCount = 0
            }
            devTapCount += 1
            if (devTapCount >= 5) {
                devTapCount = 0
                devTapStartMs = 0L
                val enabled = binding.devOverlayCard.visibility != View.VISIBLE
                binding.devOverlayCard.visibility = if (enabled) View.VISIBLE else View.GONE
                viewModel.setDevMode(enabled)
                lifecycleScope.launch { settingsManager.setDevMode(enabled) }
            }
        }

        binding.webView.setOnTouchListener { _, event ->
            kioskManager.hideSystemBars()
            handleSecretTwoFingerGesture(event)
            false
        }

        binding.offlineCard.setOnClickListener {
            if (overlayMode == OverlayMode.NoPlaylist) {
                val url = safePlayerUrl ?: buildSafePlayerUrl(deviceIdForCommands)
                runCatching { binding.webView.loadUrl(url) }
            }
        }

        setupUnlockFab()
        if (openSettings) {
            binding.webView.post { openSettingsPanel() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                    kioskManager.hideSystemBars()
                    if (!unlockDialogShowing) handleBackUnlockTap()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                }
            }
        })

        runCatching {
            WebViewConfigurator.configure(
                context = this,
                webView = binding.webView,
                webChromeClient = CustomWebChromeClient(
                    activity = this,
                    onShowToast = { message -> AndroidBridge.showToast(this, message) },
                    onWebPermissionRequest = { req -> handleWebPermissionRequest(req) },
                ),
                webViewClient = MupaWebViewClient(
                    resolveSafeMainFrameUrl = { safePlayerUrl ?: viewModel.url.value.orEmpty() },
                    onSetupUrlBlocked = { runOnUiThread { showNoPlaylistOverlay() } },
                    onSafeMainFrameLoaded = {
                        runOnUiThread {
                            if (noPlaylistActive) {
                                noPlaylistActive = false
                                if (!viewModel.isOffline.value) setOverlayMode(OverlayMode.None)
                            }
                        }
                    },
                    onOfflineDetected = { viewModel.setOffline(true) },
                    onOnlineDetected = { viewModel.setOffline(false) },
                    onRendererCrashed = { restartWebView() },
                ),
            )
        }.onFailure {
            AndroidBridge.showToast(this, "Erro ao iniciar WebView")
        }

        val bridge = AndroidBridge(
            context = applicationContext,
            settingsManager = settingsManager,
            commands = object : AndroidBridge.Commands {
                override fun onCommandReceived(commandJson: String) {
                    viewModel.setLastCommand(commandJson)
                    binding.webView.post {
                        binding.webView.evaluateJavascript(
                            "window.confirmAndroidExecution && window.confirmAndroidExecution()",
                            null,
                        )
                    }
                }

                override fun reload() {
                    binding.webView.reload()
                }

                override fun clearCache() {
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                }

                override fun restartApp() {
                    restartApp()
                }

                override fun closeApp() {
                    finishAffinity()
                }

                override fun toggleKiosk(enabled: Boolean) {
                    lifecycleScope.launch { settingsManager.setKioskMode(enabled) }
                    kioskManager.applyNow()
                }

                override fun hideSystemBars() {
                    kioskManager.hideSystemBars()
                }

                override fun showSystemBars() {
                    kioskManager.showSystemBars()
                }
            },
        )
        binding.webView.addJavascriptInterface(bridge, "Android")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.url.collect { url ->
                        if (!url.isNullOrBlank() && binding.webView.url.isNullOrBlank()) {
                            binding.webView.loadUrl(url)
                        }
                    }
                }
                launch {
                    viewModel.isOffline.collect { offline ->
                        if (offline) {
                            setOverlayMode(OverlayMode.Offline)
                        } else {
                            if (noPlaylistActive) {
                                setOverlayMode(OverlayMode.NoPlaylist)
                            } else {
                                setOverlayMode(OverlayMode.None)
                            }
                        }
                    }
                }
                launch {
                    viewModel.devMode.collect { enabled ->
                        binding.devOverlayCard.visibility = if (enabled) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.devText.collect { text ->
                        binding.devOverlayText.text = text
                    }
                }
                launch {
                    while (isActive) {
                        val currentUrl = binding.webView.url?.trim().orEmpty()
                        if (isBlockedSetupUrl(currentUrl)) {
                            runOnUiThread { showNoPlaylistOverlay() }
                            val safe = safePlayerUrl?.trim().orEmpty()
                            if (safe.isNotBlank()) {
                                binding.webView.post {
                                    runCatching { binding.webView.stopLoading() }
                                    runCatching { binding.webView.loadUrl(safe) }
                                }
                            } else {
                                binding.webView.post { runCatching { binding.webView.stopLoading() } }
                            }
                        }
                        delay(500)
                    }
                }
            }
        }

        lifecycleScope.launch {
            val uuid = settingsManager.getOrCreateDeviceUuid()
            while (isActive) {
                val info = DeviceInfoProvider.getSnapshot(this@PlayerActivity, uuid)
                viewModel.setDevText(info.asMultilineText(viewModel.lastCommand.value, viewModel.lastAck.value))
                delay(1000)
            }
        }

        registerConnectivityCallback()

        lifecycleScope.launch {
            val persistentId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
            val fromUrl = extractDeviceIdFromPlayerUrl(extraStartUrl)?.trim().orEmpty()
            val effectiveId = if (fromUrl.isNotBlank()) fromUrl else persistentId

            deviceIdForCommands = effectiveId
            binding.deviceIdWatermark.text = "ID: $effectiveId"
            safePlayerUrl = buildSafePlayerUrl(effectiveId)
            FirebaseRealtimePolicyService.start(applicationContext, effectiveId)

            val shouldIgnoreExtra = isBlockedSetupUrl(extraStartUrl)
            val resolvedStartUrl = if (!shouldIgnoreExtra && extraStartUrl.isNotBlank()) {
                extraStartUrl
            } else {
                safePlayerUrl.orEmpty()
            }
            viewModel.setStartUrl(resolvedStartUrl)
            viewModel.load()

            MupaCommandCenter.setHandler { raw ->
                val cmd = parseCommand(raw)
                    ?: return@setHandler CommandAck(effectiveId, "error", "invalid", System.currentTimeMillis(), "invalid_json")
                executeCommand(effectiveId, cmd, sendFirebaseAck = false)
            }
            firebaseCommandService?.stop()
            val svc = FirebaseRealtimeCommandService(
                context = applicationContext,
                deviceId = effectiveId,
                onCommand = { cmd -> runOnUiThread { executeCommand(effectiveId, cmd, sendFirebaseAck = true) } },
                onAck = { ack -> runOnUiThread { viewModel.setLastAck("${ack.comando} ${ack.status} ${formatTime(ack.executado_em)}") } },
            )
            firebaseCommandService = svc
            val started = svc.start()
            if (!started) {
                viewModel.setLastAck("firebase not configured")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAlwaysOnScreen()
        wakeLockManager.acquire()
        kioskManager.applyNow()
    }

    override fun onStart() {
        super.onStart()
        if (policyReceiver == null) {
            val filter = IntentFilter(FirebaseRealtimePolicyService.ACTION_KIOSK_POLICY_UPDATED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    kioskManager.applyNow()
                }
            }
            policyReceiver = receiver
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onStop() {
        val r = policyReceiver
        if (r != null) {
            runCatching { unregisterReceiver(r) }
            policyReceiver = null
        }
        super.onStop()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        wakeLockManager.release()
        super.onPause()
    }

    override fun onDestroy() {
        MupaCommandCenter.setHandler(null)
        firebaseCommandService?.stop()
        firebaseCommandService = null
        unregisterConnectivityCallback()
        wakeLockManager.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            kioskManager.applyNow()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (settingsManager.getKioskModeCached() || settingsManager.getMdmLockedCached()) {
            kioskManager.hideSystemBars()
        }
    }

    private fun showNoPlaylistOverlay() {
        noPlaylistActive = true
        setOverlayMode(OverlayMode.NoPlaylist)
    }

    private fun setOverlayMode(mode: OverlayMode) {
        if (overlayMode == mode) return
        overlayMode = mode

        val overlay = binding.offlineOverlay
        val title = binding.offlineTitle
        val progress = binding.offlineProgress

        when (mode) {
            OverlayMode.None -> {
                overlay.animate().alpha(0f).setDuration(220).withEndAction {
                    overlay.visibility = View.GONE
                }.start()
            }
            OverlayMode.Offline -> {
                title.text = getString(com.mupa.player.enterprise.R.string.reconnecting)
                progress.visibility = View.VISIBLE
                if (overlay.visibility != View.VISIBLE) overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(220).start()
            }
            OverlayMode.NoPlaylist -> {
                title.text =
                    "Sem playlist configurada para este dispositivo.\nAcesse o painel e vincule uma playlist.\nToque aqui para tentar novamente."
                progress.visibility = View.GONE
                if (overlay.visibility != View.VISIBLE) overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    private enum class OverlayMode { None, Offline, NoPlaylist }

    private fun startForegroundKeepAlive() {
        MupaKeepAliveService.start(this)
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255,
                )
            }
        }
    }

    private fun requestScreenCapturePermission() {
        if (screenRecordRequested) return
        screenRecordRequested = true
        applyLockTaskForScreenCapture(allowSystemUi = true)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startScreenRecord30s() {
        if (screenRecordRunning) return
        val deviceId = deviceIdForCommands.trim()
        val code = lastProjectionResultCode
        val data = lastProjectionResultData
        if (deviceId.isBlank() || code == null || data == null) return

        val i = Intent(this, ScreenRecordService::class.java).apply {
            putExtra(ScreenRecordService.EXTRA_DEVICE_ID, deviceId)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, code)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_DURATION_MS, 30_000L)
        }
        ContextCompat.startForegroundService(this, i)
        screenRecordRunning = true
        Handler(Looper.getMainLooper()).postDelayed({ screenRecordRunning = false }, 40_000L)
    }

    private fun applyLockTaskForScreenCapture(allowSystemUi: Boolean) {
        val allowed = settingsManager.getAllowedPackagesCached().toMutableSet()
        if (allowSystemUi) allowed.add("com.android.systemui")
        DeviceOwnerPolicyManager(applicationContext).applyLocked(packageName, allowed)
    }

    private suspend fun startTimelapseCapture30s() {
        if (timelapseRunning) return
        val deviceId = deviceIdForCommands.trim()
        if (deviceId.isBlank()) return

        timelapseRunning = true
        try {
            val app = FirebaseApp.initializeApp(this@PlayerActivity) ?: return
            val db = FirebaseDatabase.getInstance(app)
            val now = System.currentTimeMillis()
            val sessionId = db.getReference("device_recordings").child(deviceId).child("sessions").push().key
                ?: "s_${now}"

            val sessionRef = db.getReference("device_recordings").child(deviceId).child("sessions").child(sessionId)
            val meta = hashMapOf<String, Any?>(
                "device_id" to deviceId,
                "type" to "timelapse",
                "created_at" to now,
                "duration_ms" to 30_000L,
                "fps" to 1,
                "frame_count" to 30,
            )
            sessionRef.child("meta").setValue(meta)

            repeat(30) { idx ->
                val bmp = captureWindowBitmap() ?: return@repeat
                val payload = withContext(Dispatchers.Default) { bitmapToJpegBase64(bmp) }
                val frame = hashMapOf<String, Any?>(
                    "idx" to idx,
                    "created_at" to System.currentTimeMillis(),
                    "base64_jpeg" to payload,
                )
                sessionRef.child("frames").child(idx.toString()).setValue(frame)
                delay(1000)
            }

            val last = hashMapOf<String, Any?>(
                "device_id" to deviceId,
                "type" to "timelapse",
                "created_at" to now,
                "duration_ms" to 30_000L,
                "fps" to 1,
                "frame_count" to 30,
                "session_id" to sessionId,
            )
            db.getReference("device_recordings").child(deviceId).child("last").setValue(last)
        } finally {
            timelapseRunning = false
        }
    }

    private suspend fun captureWindowBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        val view = window.decorView
        val rect = Rect().apply { view.getWindowVisibleDisplayFrame(this) }
        val w = if (rect.width() > 0) rect.width() else view.width
        val h = if (rect.height() > 0) rect.height() else view.height
        if (w <= 0 || h <= 0) {
            cont.resume(null) {}
            return@suspendCancellableCoroutine
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(window, bitmap, { result ->
            if (cont.isCancelled) return@request
            if (result == PixelCopy.SUCCESS) cont.resume(bitmap) {} else cont.resume(null) {}
        }, handler)
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val targetW = 480
        val scale = targetW.toFloat() / bitmap.width.toFloat()
        val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 45, out)
        val bytes = out.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun registerConnectivityCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastConnectedAtElapsed = SystemClock.elapsedRealtime()
                runOnUiThread { viewModel.setOffline(false) }
            }

            override fun onLost(network: Network) {
                runOnUiThread { viewModel.setOffline(true) }
            }
        }

        cm.registerNetworkCallback(request, callback)
        connectivityCallback = callback
    }

    private fun unregisterConnectivityCallback() {
        val callback = connectivityCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(callback)
        connectivityCallback = null
    }

    private fun restartWebView() {
        binding.webView.stopLoading()
        binding.webView.reload()
    }

    private fun openNativeRegistrationAndFinish() {
        if (navigatedToRegistration) return
        navigatedToRegistration = true
        runCatching { binding.webView.stopLoading() }
        val i = Intent(this, DeviceRegistrationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
        finish()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun setupUnlockFab() {
        val target = binding.unlockHotspot
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    unlockRunnable?.let { unlockHandler.removeCallbacks(it) }
                    val r = Runnable { showUnlockDialog() }
                    unlockRunnable = r
                    unlockHandler.postDelayed(r, 8000)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    unlockRunnable?.let { unlockHandler.removeCallbacks(it) }
                    unlockRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    private fun handleSecretTwoFingerGesture(event: MotionEvent) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && !secretTwoFingerDown) {
                    secretTwoFingerDown = true
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    secretStartX = (x0 + x1) / 2f
                    secretStartY = (y0 + y1) / 2f

                    secretRunnable?.let { unlockHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (secretTwoFingerDown) {
                            showUnlockDialog()
                        }
                    }
                    secretRunnable = r
                    unlockHandler.postDelayed(r, secretHoldMs)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!secretTwoFingerDown || event.pointerCount < 2) return
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                val x1 = event.getX(1)
                val y1 = event.getY(1)
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val dx = kotlin.math.abs(cx - secretStartX)
                val dy = kotlin.math.abs(cy - secretStartY)
                if (dx > slop || dy > slop) {
                    cancelSecretGesture()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelSecretGesture()
            }
        }
    }

    private fun cancelSecretGesture() {
        secretTwoFingerDown = false
        secretRunnable?.let { unlockHandler.removeCallbacks(it) }
        secretRunnable = null
    }

    private fun showUnlockDialog() {
        if (unlockDialogShowing) return
        unlockDialogShowing = true
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Desbloquear")
            .setView(input)
            .setPositiveButton("OK") { dialog, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (entered == UNLOCK_PASSWORD) {
                    dialog.dismiss()
                    openSettingsPanel()
                } else {
                    AndroidBridge.showToast(this, "Senha incorreta")
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
            .create()
            .also { dlg ->
                dlg.setOnDismissListener { unlockDialogShowing = false }
                dlg.show()
            }
    }

    private fun handleBackUnlockTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - backUnlockStartMs > BACK_UNLOCK_WINDOW_MS) {
            backUnlockStartMs = now
            backUnlockTapCount = 0
        }
        backUnlockTapCount += 1
        if (backUnlockTapCount >= 5) {
            backUnlockTapCount = 0
            backUnlockStartMs = 0L
            showUnlockDialog()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                kioskManager.hideSystemBars()
                if (!unlockDialogShowing) handleBackUnlockTap()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openSettingsPanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val info = TextView(this).apply {
            text = "Carregando…"
            setTextColor(getColor(com.mupa.player.enterprise.R.color.enterprise_on))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val buttonsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val buttonsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val buttonsRow3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val unlockBtn = MaterialButton(this).apply {
            text = "Desbloquear"
            isAllCaps = false
        }
        unlockBtn.setOnClickListener {
            kioskManager.disableKiosk()
            AndroidBridge.showToast(this, "Desbloqueado")
        }

        val lockBtn = MaterialButton(this).apply {
            text = "Bloquear"
            isAllCaps = false
        }
        lockBtn.setOnClickListener {
            kioskManager.enableAggressiveKiosk()
            AndroidBridge.showToast(this, "Bloqueado")
        }

        val androidSettingsBtn = MaterialButton(this).apply {
            text = "Config Android"
            isAllCaps = false
        }
        androidSettingsBtn.setOnClickListener {
            if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                AndroidBridge.showToast(this, "Desbloqueie para abrir Configurações")
                return@setOnClickListener
            }
            runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                AndroidBridge.showToast(this, "Falha ao abrir Configurações")
            }
        }

        val overlayBtn = MaterialButton(this).apply {
            text = "Sobrepor apps"
            isAllCaps = false
        }
        overlayBtn.setOnClickListener {
            if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                AndroidBridge.showToast(this, "Desbloqueie para ajustar permissões")
                return@setOnClickListener
            }
            runCatching {
                val uri = Uri.parse("package:$packageName")
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                AndroidBridge.showToast(this, "Falha ao abrir permissão")
            }
        }

        val batteryBtn = MaterialButton(this).apply {
            text = "Bateria"
            isAllCaps = false
        }
        batteryBtn.setOnClickListener {
            if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                AndroidBridge.showToast(this, "Desbloqueie para ajustar permissões")
                return@setOnClickListener
            }

            val pm = getSystemService(PowerManager::class.java)
            val ignoring = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
            runCatching {
                val uri = Uri.parse("package:$packageName")
                val intent =
                    if (!ignoring) {
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
                    } else {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    }
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                AndroidBridge.showToast(this, "Falha ao abrir bateria")
            }
        }

        val commandPanelBtn = MaterialButton(this).apply {
            text = "Painel"
            isAllCaps = false
        }
        commandPanelBtn.setOnClickListener { openCommandPanel() }

        val allowedAppsBtn = MaterialButton(this).apply {
            text = "Apps"
            isAllCaps = false
        }
        allowedAppsBtn.setOnClickListener {
            startActivity(Intent(this, AllowedAppsActivity::class.java))
        }

        val copyIdBtn = MaterialButton(this).apply {
            text = "Copiar ID"
            isAllCaps = false
        }
        copyIdBtn.setOnClickListener {
            val clip = ClipData.newPlainText("device_id", deviceIdForCommands)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            AndroidBridge.showToast(this, "Copiado")
        }

        buttonsRow1.addView(unlockBtn)
        buttonsRow1.addView(lockBtn)
        buttonsRow2.addView(androidSettingsBtn)
        buttonsRow2.addView(copyIdBtn)
        buttonsRow2.addView(commandPanelBtn)
        buttonsRow2.addView(allowedAppsBtn)
        buttonsRow3.addView(overlayBtn)
        buttonsRow3.addView(batteryBtn)

        container.addView(info)
        container.addView(buttonsRow1)
        container.addView(buttonsRow2)
        container.addView(buttonsRow3)

        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Configurações")
            .setView(scroll)
            .setNegativeButton("Fechar") { d, _ -> d.dismiss() }
            .show()

        lifecycleScope.launch {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.trim().orEmpty()
            val persistentId = DeviceIdentityManager(applicationContext).getPersistentId()
            val uuid = settingsManager.getOrCreateDeviceUuid()
            val kiosk = settingsManager.getKioskModeCached()
            val mdmLocked = settingsManager.getMdmLockedCached()
            val canOverlay = runCatching { Settings.canDrawOverlays(this@PlayerActivity) }.getOrDefault(false)
            val pm = getSystemService(PowerManager::class.java)
            val ignoringBattery = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            }.getOrDefault("")

            val text = buildString {
                append("device_id: ").append(persistentId).append('\n')
                append("android_id: ").append(androidId).append('\n')
                append("device_uuid: ").append(uuid).append('\n')
                append("kiosk_mode: ").append(kiosk).append('\n')
                append("mdm_locked: ").append(mdmLocked).append('\n')
                append("overlay: ").append(canOverlay).append('\n')
                append("ignore_battery_opt: ").append(ignoringBattery).append('\n')
                append("version: ").append(version).append('\n')
                append("url: ").append(safePlayerUrl ?: "").append('\n')
            }
            info.text = text
            dialog.setTitle("Configurações • ${persistentId.take(8)}")
        }
    }

    private fun openCommandPanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val targetIdInput = EditText(this).apply {
            hint = "Device ID (serial/android_id)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("")
        }

        val commandInput = EditText(this).apply {
            hint = "JSON do comando (Firebase)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            setText(defaultCommandJson("lock_device"))
        }

        val templatesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun addTemplateButton(label: String, comando: String, url: String? = null) {
            val b = MaterialButton(this).apply {
                text = label
                isAllCaps = false
            }
            b.setOnClickListener {
                commandInput.setText(defaultCommandJson(comando, url))
            }
            templatesRow.addView(b)
        }

        addTemplateButton("Lock", "lock_device")
        addTemplateButton("Unlock", "unlock_device")
        addTemplateButton("Reload", "reset_app")
        addTemplateButton("Cache", "clear_cache")

        val openUrlButton = MaterialButton(this).apply {
            text = "Abrir URL"
            isAllCaps = false
        }
        openUrlButton.setOnClickListener {
            commandInput.setText(defaultCommandJson("abrir_url", "https://midias.mupa.app/player-consulta/${deviceIdForCommands}"))
        }
        templatesRow.addView(openUrlButton)

        val listDevicesButton = MaterialButton(this).apply {
            text = "Listar"
            isAllCaps = false
        }
        listDevicesButton.setOnClickListener {
            loadDeviceIds(
                onLoaded = { ids ->
                    if (ids.isEmpty()) {
                        AndroidBridge.showToast(this, "Nenhum dispositivo encontrado")
                        return@loadDeviceIds
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Dispositivos")
                        .setItems(ids.toTypedArray()) { d, which ->
                            targetIdInput.setText(ids[which])
                            d.dismiss()
                        }
                        .setNegativeButton("Fechar") { d, _ -> d.dismiss() }
                        .show()
                },
                onError = { AndroidBridge.showToast(this, it) },
            )
        }
        templatesRow.addView(listDevicesButton)

        container.addView(targetIdInput)
        container.addView(templatesRow)
        container.addView(commandInput)

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Painel de comandos")
            .setView(scroll)
            .setPositiveButton("Enviar") { dialog, _ ->
                val targetId = targetIdInput.text?.toString()?.trim().orEmpty()
                val raw = commandInput.text?.toString()?.trim().orEmpty()
                if (targetId.isBlank()) {
                    AndroidBridge.showToast(this, "Informe o Device ID")
                    return@setPositiveButton
                }
                if (raw.isBlank()) {
                    AndroidBridge.showToast(this, "Informe o JSON do comando")
                    return@setPositiveButton
                }
                sendFirebaseCommand(targetId, raw)
                dialog.dismiss()
            }
            .setNegativeButton("Fechar") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun defaultCommandJson(comando: String, url: String? = null): String {
        val obj = JSONObject()
            .put("comando", comando)
            .put("timestamp", System.currentTimeMillis())
        if (!url.isNullOrBlank()) obj.put("url", url)
        return obj.toString()
    }

    private fun sendFirebaseCommand(targetDeviceId: String, rawJson: String) {
        val app = FirebaseApp.initializeApp(applicationContext)
        if (app == null) {
            AndroidBridge.showToast(this, "Firebase não configurado")
            return
        }

        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
        if (obj == null) {
            AndroidBridge.showToast(this, "JSON inválido")
            return
        }

        val comando = obj.optString("comando", "").trim()
        if (comando.isBlank()) {
            AndroidBridge.showToast(this, "Campo 'comando' obrigatório")
            return
        }

        val timestamp = obj.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()

        val map = hashMapOf<String, Any?>(
            "comando" to comando,
            "pacote" to obj.optString("pacote", "").ifBlank { null },
            "codbar" to obj.optString("codbar", "").ifBlank { null },
            "ip_server" to obj.optString("ip_server", "").ifBlank { null },
            "url" to obj.optString("url", "").ifBlank { null },
            "timestamp" to timestamp,
        )

        val db = FirebaseDatabase.getInstance(app)
        db.getReference("commands")
            .child(targetDeviceId)
            .push()
            .setValue(map)
            .addOnSuccessListener { AndroidBridge.showToast(this, "Comando enviado") }
            .addOnFailureListener { AndroidBridge.showToast(this, "Falha ao enviar: ${it.javaClass.simpleName}") }
    }

    private fun loadDeviceIds(onLoaded: (List<String>) -> Unit, onError: (String) -> Unit) {
        val app = FirebaseApp.initializeApp(applicationContext)
        if (app == null) {
            onError("Firebase não configurado")
            return
        }
        val db = FirebaseDatabase.getInstance(app)
        db.getReference("dispositivos")
            .limitToFirst(80)
            .get()
            .addOnSuccessListener { snap ->
                val ids = snap.children.mapNotNull { it.key?.trim() }.filter { it.isNotBlank() }.sorted()
                onLoaded(ids)
            }
            .addOnFailureListener { onError("Falha ao listar: ${it.javaClass.simpleName}") }
    }

    private fun buildSafePlayerUrl(deviceId: String): String {
        return "https://midias.mupa.app/player-consulta/${deviceId.trim()}"
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val requiredAndroidPermissions = request.resources.mapNotNull { res ->
            when (res) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
        }.distinct()

        if (requiredAndroidPermissions.isEmpty()) {
            request.grant(request.resources)
            return
        }

        val missing = requiredAndroidPermissions.filterNot { isAndroidPermissionGranted(it) }
        if (missing.isEmpty()) {
            request.grant(request.resources)
            return
        }

        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = request
        pendingWebPermissionResources = request.resources
        pendingAndroidPermissions = missing.toTypedArray()
        webRuntimePermissionsLauncher.launch(pendingAndroidPermissions)
    }

    private fun isAndroidPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun executeCommand(deviceId: String, cmd: DeviceCommand, sendFirebaseAck: Boolean): CommandAck {
        viewModel.setLastCommand(JSONObject().apply {
            put("comando", cmd.comando ?: "")
            put("pacote", cmd.pacote ?: "")
            put("codbar", cmd.codbar ?: "")
            put("ip_server", cmd.ip_server ?: "")
            put("url", cmd.url ?: "")
            put("timestamp", cmd.timestamp)
        }.toString())

        val now = System.currentTimeMillis()
        val result = runCatching {
            when (cmd.comando) {
                "abrir_app" -> {
                    val target = cmd.pacote?.takeIf { it.isNotBlank() } ?: "com.mupa.apptc"
                    val intent = packageManager.getLaunchIntentForPackage(target)
                        ?: packageManager.getLaunchIntentForPackage("com.mupa.apptc")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } else {
                        throw IllegalStateException("app_not_found")
                    }
                }

                "consulta_ean" -> {
                    val ean = cmd.codbar?.trim().orEmpty()
                    if (ean.isBlank()) throw IllegalArgumentException("codbar_empty")
                    val js = buildString {
                        append("(function(){try{")
                        append("window.dispatchEvent(new CustomEvent('consultaEAN',{detail:{ean:'")
                        append(ean)
                        append("'}}));")
                        append("if(window.consultarProduto){window.consultarProduto('")
                        append(ean)
                        append("');}")
                        append("}catch(e){}})();")
                    }
                    binding.webView.evaluateJavascript(js, null)
                }

                "reset_app" -> {
                    binding.webView.reload()
                }

                "img_delete" -> {
                    val ean = cmd.codbar?.trim().orEmpty()
                    if (ean.isBlank()) throw IllegalArgumentException("codbar_empty")
                    val download = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                    val f = File(download, "$ean.png")
                    if (f.exists() && !f.delete()) {
                        throw IllegalStateException("delete_failed")
                    }
                }

                "ip_server" -> {
                    val ip = cmd.ip_server?.trim().orEmpty()
                    if (ip.isBlank()) throw IllegalArgumentException("ip_empty")
                    lifecycleScope.launch { settingsManager.setTcServer(ip) }
                }

                "fecha_app" -> {
                    finishAffinity()
                }

                "lock_device" -> {
                    kioskManager.enableAggressiveKiosk()
                }

                "unlock_device" -> {
                    kioskManager.disableKiosk()
                }

                "reiniciar_dispositivo", "reboot_device" -> {
                    val err = kioskManager.rebootDeviceError()
                    if (err != null) throw IllegalStateException(err)
                }

                "reiniciar" -> {
                    restartApp()
                }

                "clear_cache" -> {
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                    binding.webView.reload()
                }

                "abrir_url" -> {
                    val url = cmd.url?.trim().orEmpty()
                    if (url.isBlank()) throw IllegalArgumentException("url_empty")
                    if (isBlockedSetupUrl(url)) {
                        binding.webView.loadUrl(safePlayerUrl ?: buildSafePlayerUrl(deviceIdForCommands))
                    } else {
                        binding.webView.loadUrl(url)
                    }
                }

                "toggle_dev" -> {
                    lifecycleScope.launch { settingsManager.setDevMode(!settingsManager.getSettings().devMode) }
                }

                "dev_mode" -> {
                    lifecycleScope.launch { settingsManager.setDevMode(true) }
                    viewModel.setDevMode(true)
                    binding.devOverlayCard.visibility = View.VISIBLE
                }

                "fullscreen" -> {
                    kioskManager.hideSystemBars()
                }

                "record_screen_30s" -> {
                    if (deviceIdForCommands.isBlank()) throw IllegalStateException("device_not_ready")
                    if (screenRecordRunning) throw IllegalStateException("already_recording")
                    val locked = settingsManager.getKioskModeCached() || settingsManager.getMdmLockedCached()
                    if (locked || lastProjectionResultData == null || lastProjectionResultCode == null) {
                        lifecycleScope.launch { startTimelapseCapture30s() }
                    } else {
                        startScreenRecord30s()
                    }
                }

                else -> Unit
            }
            Unit
        }

        val ack = CommandAck(
            device_id = deviceId,
            status = if (result.isSuccess) "success" else "error",
            comando = cmd.comando ?: "unknown",
            executado_em = now,
            detalhe = result.exceptionOrNull()?.message,
        )
        if (sendFirebaseAck) {
            firebaseCommandService?.sendAck(ack)
        }
        viewModel.setLastAck("${ack.comando} ${ack.status} ${formatTime(ack.executado_em)}")
        return ack
    }

    private fun parseCommand(json: String): DeviceCommand? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val comando = obj.optString("comando", "").ifBlank { null } ?: return null
        val ts = obj.optLong("timestamp", 0L)
        return DeviceCommand(
            comando = comando,
            pacote = obj.optString("pacote", "").ifBlank { null },
            codbar = obj.optString("codbar", "").ifBlank { null },
            ip_server = obj.optString("ip_server", "").ifBlank { null },
            url = obj.optString("url", "").ifBlank { null },
            timestamp = ts,
        )
    }

    private fun formatTime(epochMs: Long): String {
        val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return runCatching { df.format(Date(epochMs)) }.getOrDefault(epochMs.toString())
    }

    private fun isBlockedSetupUrl(rawUrl: String): Boolean {
        val raw = rawUrl.trim()
        if (raw.isBlank()) return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null) {
            return raw.contains("/setup", ignoreCase = true) || raw.contains("#/setup", ignoreCase = true)
        }

        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "midias.mupa.app" || host.endsWith(".midias.mupa.app"))) return false

        val path = uri.path.orEmpty()
        if (path == "/setup" || path.startsWith("/setup/")) return true

        val frag = uri.fragment?.lowercase().orEmpty().trim()
        if (frag.isNotBlank()) {
            val normalized = frag.trimStart('/')
            if (normalized == "setup" || normalized.startsWith("setup/")) return true
            if (normalized.contains("/setup")) return true
        }

        return false
    }

    private fun extractDeviceIdFromPlayerUrl(rawUrl: String): String? {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "midias.mupa.app" || host.endsWith(".midias.mupa.app"))) return null
        val segs = uri.pathSegments
        val idx = segs.indexOf("player-consulta")
        if (idx < 0) return null
        val id = segs.getOrNull(idx + 1)?.trim().orEmpty()
        return id.ifBlank { null }
    }

    companion object {
        const val EXTRA_START_URL = "extra_start_url"
        const val EXTRA_OPEN_SETTINGS_PANEL = "extra_open_settings_panel"
        const val UNLOCK_PASSWORD = "040816050223"
        private const val BACK_UNLOCK_WINDOW_MS = 6000L
    }
}
