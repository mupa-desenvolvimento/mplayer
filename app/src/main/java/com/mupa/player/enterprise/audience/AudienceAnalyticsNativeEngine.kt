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

    private data class TrackedFace(
        val hash: String,
        var embedding: FloatArray,
        var lastSeenTimeMs: Long,
        var attentionDurationMs: Long = 0L,
        var lastLookStartedAtMs: Long? = null
    )
    private val trackedFaces = ArrayList<TrackedFace>()

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0.0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return Math.sqrt(sum.toDouble()).toFloat()
    }

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
            faceDetector = FaceDetection.getClient(options)

            // TFLite (idade/gênero e reconhecimento facial) desativado temporariamente: o
            // Interpreter.run()/runForMultipleInputsOutputs() estava causando SIGSEGV nativo
            // (libtensorflowlite_jni.so) que derrubava o processo inteiro do app repetidamente
            // em produção. Enquanto a causa raiz não é corrigida, seguimos só com a detecção de
            // rosto do ML Kit (isLooking / bounding box) — idade, gênero e faceHash caem no
            // fallback heurístico já existente mais abaixo.
            // val ageGenderFile = File(modelsDir, "age_gender_model.tflite")
            // if (ageGenderFile.exists()) {
            //     ageGenderInterpreter = Interpreter(ageGenderFile)
            // }
            //
            // val faceRecFile = File(modelsDir, "mobilefacenet.tflite")
            // if (faceRecFile.exists()) {
            //     faceRecInterpreter = Interpreter(faceRecFile)
            // }
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
                    val resized = Bitmap.createScaledBitmap(faceBitmap, 80, 80, true)
                    val inputBuffer = prepareByteBuffer(resized, 80, 80)
                    val genderOut = Array(1) { FloatArray(2) }
                    val ageOut = Array(1) { FloatArray(4) }
                    val outputs = mapOf(0 to genderOut, 1 to ageOut)
                    ageGenderInterpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
                    
                    val femaleProb = genderOut[0][0]
                    val maleProb = genderOut[0][1]
                    if (maleProb > femaleProb) {
                        gender = "Male"
                        confidence = maleProb
                    } else {
                        gender = "Female"
                        confidence = femaleProb
                    }

                    var maxAgeIdx = 0
                    var maxAgeVal = ageOut[0][0]
                    for (i in 1..3) {
                        if (ageOut[0][i] > maxAgeVal) {
                            maxAgeVal = ageOut[0][i]
                            maxAgeIdx = i
                        }
                    }
                    estimatedAge = when (maxAgeIdx) {
                        0 -> 10
                        1 -> 26
                        2 -> 42
                        else -> 65
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

            // 3. Face Recognition & Embedding (faceHash)
            var faceHash: String? = null
            var descriptor: FloatArray? = null
            var currentAttentionDurationMs = 0L
            if (faceBitmap != null && faceRecInterpreter != null) {
                try {
                    val resized = Bitmap.createScaledBitmap(faceBitmap, 112, 112, true)
                    val inputBuffer = prepareByteBuffer(resized, 112, 112)
                    val outputEmbedding = Array(1) { FloatArray(192) }
                    faceRecInterpreter?.run(inputBuffer, outputEmbedding)

                    val desc = outputEmbedding[0]
                    descriptor = desc
                    
                    val now = System.currentTimeMillis()
                    // Remove tracked faces older than 1 hour
                    synchronized(trackedFaces) {
                        trackedFaces.removeAll { now - it.lastSeenTimeMs > 3600000L }

                        // Try to match with an existing tracked face
                        var matchedFace: TrackedFace? = null
                        var bestDist = Float.MAX_VALUE
                        for (tf in trackedFaces) {
                            val dist = euclideanDistance(desc, tf.embedding)
                            android.util.Log.d("FaceRecognitionTest", "Distancia Euclidiana para ${tf.hash}: $dist")
                            if (dist < 1.25f && dist < bestDist) {
                                bestDist = dist
                                matchedFace = tf
                            }
                        }

                        if (matchedFace != null) {
                            faceHash = matchedFace.hash
                            val lastSeen = matchedFace.lastSeenTimeMs
                            val timeSinceLastSeen = now - lastSeen
                            val lastLook = if (timeSinceLastSeen > 1500L) null else matchedFace.lastLookStartedAtMs

                            if (isLooking) {
                                if (lastLook != null) {
                                    matchedFace.attentionDurationMs += timeSinceLastSeen
                                }
                                matchedFace.lastLookStartedAtMs = now
                            } else {
                                matchedFace.lastLookStartedAtMs = null
                            }
                            matchedFace.embedding = desc // update embedding
                            matchedFace.lastSeenTimeMs = now
                            currentAttentionDurationMs = matchedFace.attentionDurationMs
                        } else {
                            val reduced = StringBuilder()
                            for (j in desc.indices step 4) {
                                val v = Math.round((desc[j] + 1.0f) * 8.0f)
                                reduced.append(v.toChar())
                            }
                            val newHash = fnv1a(reduced.toString())
                            faceHash = newHash
                            trackedFaces.add(
                                TrackedFace(
                                    hash = newHash,
                                    embedding = desc,
                                    lastSeenTimeMs = now,
                                    attentionDurationMs = 0L,
                                    lastLookStartedAtMs = if (isLooking) now else null
                                )
                            )
                            currentAttentionDurationMs = 0L
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Fallback for faceHash
            val finalHash = faceHash ?: fnv1a("${bounds.left}_${bounds.top}_${bounds.width()}_${bounds.height()}")

            // Recycle faceBitmap since it was cropped and processed
            faceBitmap?.recycle()

            DetectedFace(
                faceHash = finalHash,
                estimatedAge = estimatedAge,
                ageRange = ageRange,
                gender = gender,
                confidence = confidence,
                isLooking = isLooking,
                embedding = descriptor,
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
