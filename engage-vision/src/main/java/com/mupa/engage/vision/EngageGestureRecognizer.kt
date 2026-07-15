package com.mupa.engage.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.mupa.engage.camera.EngageCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngageGestureRecognizer(
    context: Context,
    private val modelUrl: String = DEFAULT_MODEL_URL,
) {
    private val appContext = context.applicationContext
    private val okHttp = OkHttpClient()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var recognizer: GestureRecognizer? = null

    private var lastFingers = 0
    private var lastEmittedAtMs = 0L
    @Volatile
    private var stopping = false

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider? = null,
        onFingerCount: (Int) -> Unit,
        onEveryFrame: (fingers: Int) -> Unit = {},
        onHandLandmarks: (hands: List<List<Pair<Float, Float>>>) -> Unit = {},
    ): Boolean {
        stopping = false
        lastFingers = 0
        lastEmittedAtMs = 0L

        val provider =
            runCatching {
                withContext(Dispatchers.Default) {
                    ProcessCameraProvider.getInstance(appContext).get()
                }
            }.getOrNull() ?: return false

        val rec =
            runCatching {
                withContext(Dispatchers.Default) {
                    val modelBuffer = ensureModelMapped()
                    val baseOptions = BaseOptions.builder().setModelAssetBuffer(modelBuffer).build()
                    val options =
                        GestureRecognizer.GestureRecognizerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setResultListener { result: GestureRecognizerResult, _ ->
                                val fingers = mapResultToFingerCount(result)
                                val hands = mapResultToHandLandmarks(result)
                                onHandLandmarks(hands)
                                onEveryFrame(fingers)
                                val now = SystemClock.elapsedRealtime()
                                if (fingers != lastFingers && now - lastEmittedAtMs >= 200L) {
                                    lastFingers = fingers
                                    lastEmittedAtMs = now
                                    onFingerCount(fingers)
                                }
                            }
                            .build()
                    GestureRecognizer.createFromOptions(appContext, options)
                }
            }.getOrNull() ?: return false
        recognizer = rec

        val executorLocal = Executors.newSingleThreadExecutor()
        executor = executorLocal

        val analysisUseCase =
            EngageCamera.analysisUseCase().also { a ->
                a.setAnalyzer(executorLocal) { image ->
                    recognizeFrame(image)
                }
            }
        analysis = analysisUseCase

        val selector = EngageCamera.frontSelector()

        val previewLocal = surfaceProvider?.let {
            Preview.Builder().build().also { p -> p.setSurfaceProvider(it) }
        }
        previewUseCase = previewLocal

        return withContext(Dispatchers.Main) {
            runCatching {
                cameraProvider = provider
                provider.unbindAll()
                val useCases = listOfNotNull(previewLocal, analysisUseCase).toTypedArray()
                provider.bindToLifecycle(lifecycleOwner, selector, *useCases)
                true
            }.getOrDefault(false)
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true

        val analysisLocal = analysis
        val providerLocal = cameraProvider
        val executorLocal = executor
        val recognizerLocal = recognizer

        analysis = null
        previewUseCase = null
        cameraProvider = null
        executor = null
        recognizer = null

        runCatching { analysisLocal?.clearAnalyzer() }
        runCatching { providerLocal?.unbindAll() }

        Thread {
            runCatching {
                executorLocal?.shutdown()
                executorLocal?.awaitTermination(600, TimeUnit.MILLISECONDS)
            }
            runCatching { executorLocal?.shutdownNow() }
            runCatching { recognizerLocal?.close() }
        }.start()
    }

    private fun recognizeFrame(imageProxy: ImageProxy) {
        val rec = recognizer
        if (rec == null || stopping) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val w = imageProxy.width
        val h = imageProxy.height

        val bitmap =
            runCatching {
                if (imageProxy.format == ImageFormat.YUV_420_888) {
                    yuv420ToBitmap(imageProxy)
                } else {
                    rgba8888ToBitmap(imageProxy)
                }
            }.getOrNull()

        runCatching { imageProxy.close() }

        if (bitmap == null) return

        val matrix =
            Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(-1f, 1f, w.toFloat(), h.toFloat())
            }
        val rotatedBitmap =
            runCatching {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
            }.getOrNull() ?: return

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        runCatching { rec.recognizeAsync(mpImage, frameTime) }
    }

    private fun rgba8888ToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        return bitmap
    }

    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        val w = imageProxy.width
        val h = imageProxy.height
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        val ok = yuvImage.compressToJpeg(Rect(0, 0, w, h), 80, out)
        if (!ok) return null
        val jpeg = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun mapResultToFingerCount(result: GestureRecognizerResult): Int {
        val gestures: List<List<Category>> = runCatching { result.gestures() }.getOrDefault(emptyList())
        val top = gestures.firstOrNull()?.firstOrNull() ?: return 0
        val name = top.categoryName()?.trim().orEmpty()
        return when (name) {
            "Pointing_Up" -> 1
            "Victory" -> 2
            else -> 0
        }
    }

    private fun mapResultToHandLandmarks(result: GestureRecognizerResult): List<List<Pair<Float, Float>>> {
        val landmarks: List<List<NormalizedLandmark>> =
            runCatching { result.landmarks() }.getOrDefault(emptyList())
        return landmarks.map { hand ->
            hand.map { lm -> Pair(lm.x(), lm.y()) }
        }
    }

    private fun ensureModelMapped(): MappedByteBuffer {
        val dir = File(appContext.filesDir, "engage_models").apply { mkdirs() }
        val file = File(dir, "gesture_recognizer.task")
        if (!file.exists() || file.length() <= 0L) {
            downloadTo(file)
        }
        val raf = RandomAccessFile(file, "r")
        return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).also {
            runCatching { raf.close() }
        }
    }

    private fun downloadTo(target: File) {
        val req = Request.Builder().url(modelUrl).build()
        okHttp.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("download_failed:${res.code}")
            val body = res.body ?: throw IllegalStateException("download_empty")
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                body.byteStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
    }
}
