package com.mupa.player.enterprise.audience

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mupa.player.enterprise.storage.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AudienceAnalyticsManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val deviceId: String,
    private val contentPlayingProvider: () -> String?,
    private val playlistProvider: () -> String?,
) {
    private val modelsDir = File(context.filesDir, "models")
    private val web = AudienceAnalyticsWebViewEngine(context, modelsDir)
    private val tracker = ViewingSessionTracker(deviceId)
    private val db = AppDatabase.get(context)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var lastProcessedAtMs = 0L

    suspend fun startIfPossible(): Boolean {
        if (!canRunOnDevice(context)) return false
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val ok = web.init()
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

        web.release()
    }

    private fun onImage(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedAtMs < 1000L) {
            image.close()
            return
        }
        lastProcessedAtMs = now

        val jpeg = runCatching { YuvToJpeg.imageProxyToJpegBytes(image, jpegQuality = 55) }.getOrNull()
        image.close()
        if (jpeg == null || jpeg.isEmpty()) return

        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        scope.launch {
            val result = runCatching { web.processFrameJpegBase64(base64) }.getOrNull()
                ?: AudienceFrameResult(emptyList())

            val ended = tracker.onFrame(
                frame = result,
                contentPlaying = contentPlayingProvider(),
                playlist = playlistProvider(),
                expireAfterMs = 3000L,
            )

            ended.forEach { db.audienceSessionDao().upsert(it) }
        }
    }

    companion object {
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
