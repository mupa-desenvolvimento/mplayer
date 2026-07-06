package com.mupa.player.enterprise.audience

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AudienceAnalyticsManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val deviceId: String,
    private val contentPlayingProvider: () -> String?,
    private val playlistProvider: () -> String?,
    private val engine: AudienceAnalyticsEngine = AudienceAnalyticsNativeEngine(context, File(context.filesDir, "models")),
) {
    private val modelsDir = File(context.filesDir, "models")
    private val tracker = ViewingSessionTracker(deviceId)
    private val db = AppDatabase.get(context)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var lastProcessedAtMs = 0L

    suspend fun startIfPossible(): Boolean {
        if (!canRunOnDevice(context)) return false
        val cache = runCatching { DeviceCacheManager(context).load() }.getOrNull()
        val licenseType = cache?.tipoDaLicenca?.trim()?.lowercase(Locale.US)
        val licenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
        if (!licenseValid) return false

        if (!modelsDir.exists()) modelsDir.mkdirs()

        // Provision TFLite models before engine initialization
        ModelProvisioningManager.ensureModelsProvisioned(context, cache?.tipoDaLicenca)

        val ok = engine.init()
        if (!ok) return false

        val provider =
            runCatching {
                withContext(Dispatchers.Default) {
                    ProcessCameraProvider.getInstance(context).get()
                }
            }.getOrNull() ?: return false

        val executorLocal = Executors.newSingleThreadExecutor()
        executor = executorLocal

        val analysisUseCase =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        analysis = analysisUseCase

        analysisUseCase.setAnalyzer(executorLocal) { image ->
            onImage(image)
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        return withContext(Dispatchers.Main) {
            runCatching {
                cameraProvider = provider
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysisUseCase)
                true
            }.getOrDefault(false)
        }
    }

    suspend fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysis?.clearAnalyzer()
        analysis = null
        executor?.shutdownNow()
        executor = null

        val ended = tracker.flushAll()
        ended.forEach { db.audienceSessionDao().upsert(it) }

        engine.release()
    }

    @Volatile private var isFaceActive = false
    @Volatile private var isProcessing = false

    private fun onImage(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (isProcessing) {
            image.close()
            return
        }

        val delayMs = if (isFaceActive) 33L else 200L // 30 FPS vs 5 FPS
        if (now - lastProcessedAtMs < delayMs) {
            image.close()
            return
        }
        isProcessing = true
        lastProcessedAtMs = now

        val rotation = image.imageInfo.rotationDegrees
        val jpeg = runCatching { YuvToJpeg.imageProxyToJpegBytes(image, jpegQuality = 55) }.getOrNull()
        image.close()
        if (jpeg == null || jpeg.isEmpty()) {
            isProcessing = false
            return
        }

        val base64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)

        scope.launch {
            try {
                val result = runCatching { engine.processFrameJpegBase64(base64, rotation) }.getOrNull()
                    ?: AudienceFrameResult(emptyList())

                isFaceActive = result.faces.isNotEmpty()

                val ended = tracker.onFrame(
                    frame = result,
                    contentPlaying = contentPlayingProvider(),
                    playlist = playlistProvider(),
                    expireAfterMs = 3000L,
                )

                ended.forEach { db.audienceSessionDao().upsert(it) }
            } finally {
                isProcessing = false
            }
        }
    }

    companion object {
        // Valid license types that enable facial recognition
        private val VALID_LICENSE_TYPES = setOf("facial", "analytics", "enterprise")

        fun isLicenseValid(tipoDaLicenca: String?): Boolean {
            return tipoDaLicenca?.trim()?.lowercase(Locale.US) in VALID_LICENSE_TYPES
        }

        fun canRunOnDevice(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return hasUsableFrontCamera(context)
        }

        fun hasUsableFrontCamera(context: Context): Boolean {
            val pm = context.packageManager
            val hasAny =
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                    pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
                    pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            if (!hasAny) return false

            val cm =
                ContextCompat.getSystemService(context, CameraManager::class.java)
                    ?: return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            return runCatching {
                cm.cameraIdList.any { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
            }.getOrDefault(false)
        }

        fun hasCameraPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
