package com.mupa.player.enterprise.audience

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudienceAnalyticsNativeEngine(
    private val context: Context,
    private val modelsDir: File,
) {
    private var faceDetector: FaceDetector? = null
    private var ageGenderInterpreter: Interpreter? = null
    private var faceRecInterpreter: Interpreter? = null

    // Lidas do próprio modelo em init(). Modelo real: input 80×80×3, 2 saídas —
    // out0=[1,2] gênero [female,male], out1=[1,4] idade (4 faixas).
    private var ageGenderInputSize = 80
    private var genderOutLen = 2
    private var ageOutLen = 4
    // O Interpreter do TFLite NÃO é thread-safe; o processFrame roda em Dispatchers.Default e
    // pode ser chamado concorrentemente — acesso concorrente ao interpreter causava o SIGSEGV
    // nativo. Serializamos toda chamada ao modelo por este lock.
    private val ageGenderLock = Any()

    // Estado de atenção por pessoa, indexado pelo trackingId ESTÁVEL do ML Kit (não mais por
    // embedding do TFLite, que estava desativado e fazia o id "pular" a cada frame).
    private data class TrackedFace(
        var lastSeenTimeMs: Long,
        var attentionDurationMs: Long = 0L,
        var lastLookStartedAtMs: Long? = null
    )
    private val trackedFaces = HashMap<Int, TrackedFace>()

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // Tracking dá um trackingId ESTÁVEL por rosto entre frames enquanto a pessoa
                // continua no enquadramento. É o que fixa a identidade pra somar tempo por
                // pessoa — sem depender do modelo TFLite de reconhecimento (que está desativado).
                .enableTracking()
                .build()
            faceDetector = FaceDetection.getClient(options)

            // Idade/gênero (TFLite). O SIGSEGV anterior era por shape errada (código assumia
            // input 80×80 + 2 saídas, mas o modelo é 224×224 + 1 saída [age, male, female]).
            // Agora lemos input/output do próprio modelo e dimensionamos os buffers pela shape
            // REAL — o buffer nunca desalinha, então não há mais estouro nativo.
            val ageGenderFile = File(modelsDir, "age_gender_model.tflite")
            if (ageGenderFile.exists()) {
                runCatching {
                    val interp = Interpreter(ageGenderFile, Interpreter.Options().apply { setNumThreads(2) })
                    val inShape = interp.getInputTensor(0).shape()   // [1, H, W, 3]
                    ageGenderInputSize = if (inShape.size >= 3) inShape[1] else 80
                    if (interp.outputTensorCount >= 2) {
                        genderOutLen = interp.getOutputTensor(0).shape().lastOrNull() ?: 2
                        ageOutLen = interp.getOutputTensor(1).shape().lastOrNull() ?: 4
                    }
                    android.util.Log.i(
                        "AudienceEngine",
                        "age_gender carregado: input=${inShape.joinToString("x")} genderLen=$genderOutLen ageLen=$ageOutLen",
                    )
                    ageGenderInterpreter = interp
                }.onFailure {
                    android.util.Log.w("AudienceEngine", "falha ao carregar age_gender_model: ${it.message}")
                    ageGenderInterpreter = null
                }
            } else {
                android.util.Log.w("AudienceEngine", "age_gender_model.tflite não encontrado em $modelsDir")
            }

            // Reconhecimento facial (mobilefacenet) segue desativado — a identidade por sessão
            // usa o trackingId do ML Kit. Só seria necessário pra visitante único/recorrência.
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun processFrameJpegBase64(base64Jpeg: String, rotationDegrees: Int = 0): AudienceFrameResult = withContext(Dispatchers.Default) {
        val detector = faceDetector ?: return@withContext AudienceFrameResult(emptyList())
        val jpegBytes = try {
            Base64.decode(base64Jpeg, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withContext AudienceFrameResult(emptyList())
        }

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return@withContext AudienceFrameResult(emptyList())

        val rotatedBitmap = if (rotationDegrees != 0) {
            try {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it != bitmap) {
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                bitmap
            }
        } else {
            bitmap
        }

        val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
        val faces = try {
            detector.process(inputImage).awaitTask()
        } catch (e: Exception) {
            e.printStackTrace()
            if (!rotatedBitmap.isRecycled) {
                rotatedBitmap.recycle()
            }
            return@withContext AudienceFrameResult(emptyList())
        }

        val detectedFaces = faces.map { face ->
            val bounds = face.boundingBox
            val x = bounds.left.coerceAtLeast(0)
            val y = bounds.top.coerceAtLeast(0)
            val w = bounds.width().coerceAtMost(rotatedBitmap.width - x)
            val h = bounds.height().coerceAtMost(rotatedBitmap.height - y)

            // Só recorta o rosto quando algum modelo TFLite está carregado (idade/gênero ou
            // reconhecimento). Sem eles, o crop era criado e reciclado à toa a cada rosto/frame.
            val needsCrop = ageGenderInterpreter != null || faceRecInterpreter != null
            val faceBitmap = if (needsCrop && w > 0 && h > 0) {
                Bitmap.createBitmap(rotatedBitmap, x, y, w, h)
            } else {
                null
            }

            // 1. Look detection
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
            val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)

            val isLooking = if (leftEye != null && rightEye != null && noseBase != null) {
                val le = leftEye.position
                val re = rightEye.position
                val nx = noseBase.position.x
                val midX = (le.x + re.x) / 2.0f
                val eyeDist = Math.abs(re.x - le.x)
                if (eyeDist > 0.1f) {
                    val ratio = Math.abs(nx - midX) / eyeDist
                    (ratio * 90.0f) < 25.0f
                } else {
                    Math.abs(face.headEulerAngleY) < 25.0f
                }
            } else {
                Math.abs(face.headEulerAngleY) < 25.0f
            }

            // 2. TFLite Age and Gender
            var estimatedAge: Int? = null
            var gender: String? = null
            var confidence: Float? = null

            if (faceBitmap != null && ageGenderInterpreter != null) {
                try {
                    val size = ageGenderInputSize
                    val resized = Bitmap.createScaledBitmap(faceBitmap, size, size, true)
                    val inputBuffer = prepareByteBuffer(resized, size, size)
                    // 2 saídas: out0=gênero [female, male], out1=idade (faixas / softmax).
                    val genderOut = Array(1) { FloatArray(genderOutLen) }
                    val ageOut = Array(1) { FloatArray(ageOutLen) }
                    val outputs = mapOf(0 to genderOut, 1 to ageOut)
                    // Serializado: o Interpreter TFLite não é thread-safe (causa do SIGSEGV).
                    synchronized(ageGenderLock) {
                        ageGenderInterpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
                    }

                    val femaleProb = genderOut[0][0]
                    val maleProb = genderOut[0].getOrElse(1) { 0f }
                    if (maleProb >= femaleProb) {
                        gender = "Male"; confidence = maleProb
                    } else {
                        gender = "Female"; confidence = femaleProb
                    }

                    // Idade: argmax das faixas → idade representativa da faixa.
                    val ages = ageOut[0]
                    var maxIdx = 0
                    for (i in 1 until ages.size) if (ages[i] > ages[maxIdx]) maxIdx = i
                    estimatedAge = when (maxIdx) {
                        0 -> 10   // criança
                        1 -> 26   // jovem adulto
                        2 -> 42   // adulto
                        else -> 65 // sênior
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Sem TFLite de idade/gênero, NÃO inventamos valores: idade/gênero/confiança ficam
            // null (desconhecido) em vez dos fallbacks heurísticos aleatórios anteriores, que
            // poluíam a analytics (a mesma pessoa mais perto/longe mudava de "idade"/"gênero").
            // A contagem de rostos e o isLooking (ML Kit) continuam válidos.
            val ageRange = estimatedAge?.let { age ->
                when {
                    age < 18 -> "0-17"
                    age < 25 -> "18-24"
                    age < 35 -> "25-34"
                    age < 45 -> "35-44"
                    age < 55 -> "45-54"
                    age < 65 -> "55-64"
                    else -> "65+"
                }
            }

            // 3. Identidade estável + tempo de atenção via trackingId do ML Kit.
            // O trackingId persiste enquanto a MESMA pessoa continua no enquadramento, então o
            // faceHash não "pula" mais a cada frame e o tempo por pessoa é acumulado corretamente.
            val trackingId = face.trackingId
            val now = System.currentTimeMillis()
            var currentAttentionDurationMs = 0L
            val finalHash: String
            if (trackingId != null) {
                finalHash = "mlkit_$trackingId"
                synchronized(trackedFaces) {
                    // Descarta quem saiu de vista há mais de 1h.
                    trackedFaces.entries.removeAll { now - it.value.lastSeenTimeMs > 3600000L }
                    val tf = trackedFaces.getOrPut(trackingId) {
                        TrackedFace(lastSeenTimeMs = now, lastLookStartedAtMs = if (isLooking) now else null)
                    }
                    val timeSinceLastSeen = now - tf.lastSeenTimeMs
                    // Só acumula se a visão foi contínua (gap curto) e a pessoa estava olhando.
                    val continuous = timeSinceLastSeen in 0..1500L
                    if (isLooking) {
                        if (continuous && tf.lastLookStartedAtMs != null) {
                            tf.attentionDurationMs += timeSinceLastSeen
                        }
                        tf.lastLookStartedAtMs = now
                    } else {
                        tf.lastLookStartedAtMs = null
                    }
                    tf.lastSeenTimeMs = now
                    currentAttentionDurationMs = tf.attentionDurationMs
                }
            } else {
                // Sem trackingId (raro com tracking ligado) — id posicional, sem somar tempo.
                finalHash = fnv1a("${bounds.left}_${bounds.top}_${bounds.width()}_${bounds.height()}")
            }

            // faceBitmap só existe quando algum TFLite está carregado; recicla se foi criado.
            faceBitmap?.recycle()

            DetectedFace(
                faceHash = finalHash,
                estimatedAge = estimatedAge,
                ageRange = ageRange,
                gender = gender,
                confidence = confidence,
                isLooking = isLooking,
                embedding = null,
                attentionDurationSeconds = currentAttentionDurationMs / 1000L,
                boundingBox = bounds,
            )
        }

        val frameWidth = rotatedBitmap.width
        val frameHeight = rotatedBitmap.height

        if (!rotatedBitmap.isRecycled) {
            rotatedBitmap.recycle()
        }

        AudienceFrameResult(
            faces = detectedFaces,
            width = frameWidth,
            height = frameHeight,
        )
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        faceDetector?.close()
        faceDetector = null
        ageGenderInterpreter?.close()
        ageGenderInterpreter = null
        faceRecInterpreter?.close()
        faceRecInterpreter = null
    }

    private fun prepareByteBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * width * height * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)
        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    private fun fnv1a(str: String): String {
        var h = 0x811c9dc5L
        for (i in str.indices) {
            h = h xor str[i].code.toLong()
            h = (h * 0x01000193) and 0xffffffffL
        }
        return String.format("%08x", h)
    }

    private suspend fun <T> Task<T>.awaitTask(): T {
        val deferred = CompletableDeferred<T>()
        addOnSuccessListener { result ->
            deferred.complete(result)
        }
        addOnFailureListener { exception ->
            deferred.completeExceptionally(exception)
        }
        return deferred.await()
    }
}
