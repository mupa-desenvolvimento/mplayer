package com.mupa.player.enterprise.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.audience.AudienceAnalyticsNativeEngine
import com.mupa.player.enterprise.audience.ModelProvisioningManager
import com.mupa.player.enterprise.audience.YuvToJpeg
import com.mupa.player.enterprise.databinding.ActivityFaceRecognitionTestBinding
import com.mupa.player.enterprise.managers.DeviceCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceRecognitionTestActivity : ComponentActivity() {

    private lateinit var binding: ActivityFaceRecognitionTestBinding
    private var nativeEngine: AudienceAnalyticsNativeEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var lastProcessedAtMs = 0L
    @Volatile private var isFaceActive = false
    @Volatile private var isProcessing = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraAndEngine()
        } else {
            Toast.makeText(this, "Permissão de câmera é necessária para o teste", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceRecognitionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Painel de métricas começa oculto; botão alterna entre expandir/recolher.
        binding.metricsContent.visibility = View.GONE
        binding.btnToggleMetrics.setOnClickListener {
            val expanded = binding.metricsContent.visibility == View.VISIBLE
            binding.metricsContent.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.btnToggleMetrics.setIconResource(
                if (expanded) android.R.drawable.arrow_down_float
                else android.R.drawable.arrow_up_float
            )
        }

        binding.btnDownloadModels.setOnClickListener {
            binding.btnDownloadModels.isEnabled = false
            lifecycleScope.launch {
                binding.txtCameraStatus.text = "Status: Baixando modelos..."
                val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
                val ok = ModelProvisioningManager.ensureModelsProvisioned(applicationContext, cache?.tipoDaLicenca ?: "facial")
                if (ok) {
                    Toast.makeText(this@FaceRecognitionTestActivity, "Modelos baixados com sucesso!", Toast.LENGTH_SHORT).show()
                    startCameraAndEngine()
                } else {
                    Toast.makeText(this@FaceRecognitionTestActivity, "Falha ao baixar modelos.", Toast.LENGTH_SHORT).show()
                    binding.txtCameraStatus.text = "Status: Falha no download"
                }
                binding.btnDownloadModels.isEnabled = true
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndEngine()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraAndEngine() {
        binding.txtCameraStatus.text = "Status: Provisionando modelos..."
        binding.txtModelStatus.text = "Modelos:\n- Verificando..."
        lifecycleScope.launch {
            val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
            
            // Check models provisioning
            val modelsDir = File(filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            // Run provisioning
            binding.txtCameraStatus.text = "Status: Verificando/Provisionando modelos TFLite..."
            ModelProvisioningManager.ensureModelsProvisioned(applicationContext, cache?.tipoDaLicenca ?: "facial")
            
            // Verify models existence and size
            val ageGenderFile = File(modelsDir, "age_gender_model.tflite")
            val faceRecFile = File(modelsDir, "mobilefacenet.tflite")
            val ageGenderOk = ageGenderFile.exists() && ageGenderFile.length() > 0L
            val faceRecOk = faceRecFile.exists() && faceRecFile.length() > 0L

            // Instantiate engine
            val engine = AudienceAnalyticsNativeEngine(applicationContext, modelsDir)
            val initialized = engine.init()

            val checklist = StringBuilder().apply {
                append("Status dos Modelos:\n")
                append("- Idade/Gênero (age_gender_model.tflite): ")
                if (ageGenderOk) append("[SUCESSO]\n") else append("[FALHA]\n")
                append("- Identificação (mobilefacenet.tflite): ")
                if (faceRecOk) append("[SUCESSO]\n") else append("[FALHA]\n")
                append("- Motor Nativo (ML Engine): ")
                if (initialized) append("[SUCESSO]\n") else append("[FALHA]\n")
                append("- Licença Local Cacheada: [${cache?.tipoDaLicenca ?: "NENHUMA"}]")
            }.toString()

            withContext(Dispatchers.Main) {
                binding.txtModelStatus.text = checklist
            }

            if (!initialized) {
                binding.txtCameraStatus.text = "Status: Falha ao inicializar AudienceAnalyticsNativeEngine"
                Toast.makeText(this@FaceRecognitionTestActivity, "Falha na inicialização do Engine nativo", Toast.LENGTH_LONG).show()
                return@launch
            }

            nativeEngine = engine
            binding.txtCameraStatus.text = "Status: Engine pronto. Inicializando CameraX..."
            setupCamera()
        }
    }

    private fun setupCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val executorLocal = Executors.newSingleThreadExecutor()
                executor = executorLocal

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysisUseCase.setAnalyzer(executorLocal) { image ->
                    val now = System.currentTimeMillis()
                    if (isProcessing) {
                        image.close()
                        return@setAnalyzer
                    }
                    val delayMs = if (isFaceActive) 33L else 200L // 30 FPS vs 5 FPS
                    if (now - lastProcessedAtMs < delayMs) {
                        image.close()
                        return@setAnalyzer
                    }
                    isProcessing = true
                    lastProcessedAtMs = now

                    val rotation = image.imageInfo.rotationDegrees
                    val jpegResult = runCatching { YuvToJpeg.imageProxyToJpegBytes(image, jpegQuality = 55) }
                    val jpeg = jpegResult.getOrNull()
                    if (jpegResult.isFailure) {
                        Log.e("FaceRecognitionTest", "YuvToJpeg conversion failed", jpegResult.exceptionOrNull())
                    }
                    image.close()

                    if (jpeg == null || jpeg.isEmpty()) {
                        isProcessing = false
                        return@setAnalyzer
                    }

                    val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    lifecycleScope.launch {
                        try {
                            val engine = nativeEngine
                            if (engine != null) {
                                val resultResult = runCatching { engine.processFrameJpegBase64(base64, rotation) }
                                val result = resultResult.getOrNull()
                                if (resultResult.isFailure) {
                                    Log.e("FaceRecognitionTest", "processFrameJpegBase64 failed", resultResult.exceptionOrNull())
                                }
                                withContext(Dispatchers.Main) {
                                    if (result != null && result.faces.isNotEmpty()) {
                                        isFaceActive = true
                                        val builder = StringBuilder()
                                        val drawFaces = ArrayList<FaceOverlayView.DrawFace>()
                                        result.faces.forEachIndexed { index, face ->
                                            // Linha compacta por rosto (debug enxuto): gênero, idade,
                                            // tempo de atenção e se está olhando.
                                            val g = when (face.gender) {
                                                "Male" -> "M"; "Female" -> "F"; else -> "?"
                                            }
                                            val age = face.estimatedAge?.let { "${it}a" } ?: "?"
                                            val look = if (face.isLooking) "olhando" else "—"
                                            builder.append("#${index + 1}  $g · $age · ${face.attentionDurationSeconds}s · $look\n")

                                            face.boundingBox?.let { rect ->
                                                drawFaces.add(
                                                    FaceOverlayView.DrawFace(
                                                        rect = rect,
                                                        // Tag curta sobre o rosto: gênero + idade.
                                                        label = "$g $age"
                                                    )
                                                )
                                            }
                                        }
                                        binding.faceOverlayView.updateFaces(drawFaces, result.width, result.height)
                                        binding.txtDetectionDetails.text = builder.toString()
                                        binding.txtCameraStatus.text = "Status: Rostos detectados (${result.faces.size})"
                                    } else {
                                        isFaceActive = false
                                        binding.faceOverlayView.updateFaces(emptyList(), 0, 0)
                                        binding.txtDetectionDetails.text = "Nenhum rosto detectado no frame."
                                        binding.txtCameraStatus.text = "Status: Nenhum rosto"
                                    }
                                }
                            }
                        } finally {
                            isProcessing = false
                        }
                    }
                }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysisUseCase)
                binding.txtCameraStatus.text = "Status: Câmera iniciada com sucesso"

            } catch (e: Exception) {
                Log.e("FaceRecognitionTest", "Erro ao iniciar câmera", e)
                binding.txtCameraStatus.text = "Status: Erro ao iniciar câmera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        executor?.shutdownNow()
        lifecycleScope.launch {
            nativeEngine?.release()
        }
    }
}
