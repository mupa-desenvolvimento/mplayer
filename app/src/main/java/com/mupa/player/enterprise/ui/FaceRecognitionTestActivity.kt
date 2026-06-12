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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndEngine()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraAndEngine() {
        binding.txtCameraStatus.text = "Status: Provisionando modelos..."
        lifecycleScope.launch {
            val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
            
            // Check models provisioning
            val modelsDir = File(filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            // Run provisioning
            binding.txtCameraStatus.text = "Status: Verificando/Provisionando modelos TFLite..."
            val provisioned = ModelProvisioningManager.ensureModelsProvisioned(applicationContext, cache?.tipoDaLicenca)
            
            // Instantiate engine
            val engine = AudienceAnalyticsNativeEngine(applicationContext, modelsDir)
            val initialized = engine.init()

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
                    if (now - lastProcessedAtMs < 800L) {
                        image.close()
                        return@setAnalyzer
                    }
                    lastProcessedAtMs = now

                    val jpeg = runCatching { YuvToJpeg.imageProxyToJpegBytes(image, jpegQuality = 55) }.getOrNull()
                    image.close()

                    if (jpeg != null && jpeg.isNotEmpty()) {
                        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        lifecycleScope.launch {
                            val engine = nativeEngine
                            if (engine != null) {
                                val result = runCatching { engine.processFrameJpegBase64(base64) }.getOrNull()
                                withContext(Dispatchers.Main) {
                                    if (result != null && result.faces.isNotEmpty()) {
                                        val builder = StringBuilder()
                                        result.faces.forEachIndexed { index, face ->
                                            builder.append("Rosto #${index + 1}:\n")
                                            builder.append("  Hash: ${face.faceHash}\n")
                                            builder.append("  Idade (estimada): ${face.estimatedAge} (Faixa: ${face.ageRange})\n")
                                            builder.append("  Gênero: ${face.gender} (${String.format("%.2f", (face.confidence ?: 0.0f) * 100)}%)\n")
                                            builder.append("  Olhando p/ tela: ${if (face.isLooking) "SIM" else "NÃO"}\n\n")
                                        }
                                        binding.txtDetectionDetails.text = builder.toString()
                                        binding.txtCameraStatus.text = "Status: Rostos detectados (${result.faces.size})"
                                    } else {
                                        binding.txtDetectionDetails.text = "Nenhum rosto detectado no frame."
                                        binding.txtCameraStatus.text = "Status: Nenhum rosto"
                                    }
                                }
                            }
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
