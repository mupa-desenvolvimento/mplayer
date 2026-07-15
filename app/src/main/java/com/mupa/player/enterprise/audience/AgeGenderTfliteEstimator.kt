package com.mupa.player.enterprise.audience

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class AgeGenderTfliteEstimator(private val context: Context) {

    data class Result(
        val estimatedAge: Int?,
        val ageRange: String?,
        val gender: String?,
        val genderConfidence: Float?,
    )

    private val interpreterRef = AtomicReference<Interpreter?>(null)
    private val inputShapeRef = AtomicReference<IntArray?>(null)
    private val inputIsFloatRef = AtomicReference<Boolean?>(null)

    fun isReady(): Boolean = modelFile().exists()

    fun estimate(image: Bitmap, faceBoxNorm: RectF): Result? {
        val interpreter = ensureInterpreter() ?: return null
        val inputShape = inputShapeRef.get() ?: return null
        val inputIsFloat = inputIsFloatRef.get() ?: return null

        val w = image.width.toFloat()
        val h = image.height.toFloat()

        val left = (faceBoxNorm.left * w).roundToInt().coerceIn(0, image.width - 1)
        val top = (faceBoxNorm.top * h).roundToInt().coerceIn(0, image.height - 1)
        val right = (faceBoxNorm.right * w).roundToInt().coerceIn(left + 1, image.width)
        val bottom = (faceBoxNorm.bottom * h).roundToInt().coerceIn(top + 1, image.height)

        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)

        val inputH = inputShape.getOrNull(1) ?: return null
        val inputW = inputShape.getOrNull(2) ?: return null
        val resized = Bitmap.createScaledBitmap(crop, inputW, inputH, true)

        val inputBuffer = bitmapToInput(resized, inputIsFloat)

        val ageOut = FloatArray(1)
        val genderOut = FloatArray(2)
        val outputs = HashMap<Int, Any>()
        outputs[0] = ageOut
        outputs[1] = genderOut

        runCatching {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        }.onFailure {
            Log.w("AgeGender", "inference_failed: ${it.message}")
            return null
        }

        val age = ageOut.firstOrNull()?.takeIf { it.isFinite() && it > 0f }?.roundToInt()
        val gender =
            if (genderOut.size >= 2) {
                val p0 = genderOut[0]
                val p1 = genderOut[1]
                if (p0.isFinite() && p1.isFinite()) {
                    if (p0 >= p1) "Masculino" else "Feminino"
                } else null
            } else null
        val conf =
            if (genderOut.size >= 2) {
                val p0 = genderOut[0]
                val p1 = genderOut[1]
                if (p0.isFinite() && p1.isFinite()) maxOf(p0, p1).coerceIn(0f, 1f) else null
            } else null

        val range = age?.let { toAgeRangeLabel(it) }

        return Result(
            estimatedAge = age,
            ageRange = range,
            gender = gender,
            genderConfidence = conf,
        )
    }

    fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0) return bitmap
        val m = Matrix().apply { postRotate(r.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun ensureInterpreter(): Interpreter? {
        val existing = interpreterRef.get()
        if (existing != null) return existing

        val file = modelFile()
        if (!file.exists() || file.length() <= 0L) return null

        return synchronized(interpreterRef) {
            val again = interpreterRef.get()
            if (again != null) return@synchronized again
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            val interpreter =
                runCatching { Interpreter(file, opts) }
                    .onFailure { Log.w("AgeGender", "load_failed: ${it.message}") }
                    .getOrNull()
                    ?: return@synchronized null

            val inputTensor = interpreter.getInputTensor(0)
            val shape = inputTensor.shape()
            inputShapeRef.set(shape)
            inputIsFloatRef.set(inputTensor.dataType().name.contains("FLOAT", ignoreCase = true))
            interpreterRef.set(interpreter)
            interpreter
        }
    }

    private fun modelFile(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(baseDir, "models").apply { mkdirs() }
        return File(modelsDir, "age_gender.tflite")
    }

    private fun bitmapToInput(bmp: Bitmap, floatInput: Boolean): ByteBuffer {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val byteCount = if (floatInput) 4 else 1
        val buf = ByteBuffer.allocateDirect(1 * w * h * 3 * byteCount).order(ByteOrder.nativeOrder())
        if (floatInput) {
            for (p in pixels) {
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                buf.putFloat(r)
                buf.putFloat(g)
                buf.putFloat(b)
            }
        } else {
            for (p in pixels) {
                buf.put(((p shr 16) and 0xFF).toByte())
                buf.put(((p shr 8) and 0xFF).toByte())
                buf.put((p and 0xFF).toByte())
            }
        }
        buf.rewind()
        return buf
    }

    private fun toAgeRangeLabel(age: Int): String {
        return when {
            age < 13 -> "6-12 anos"
            age < 18 -> "13-17 anos"
            age < 25 -> "18-24 anos"
            age < 35 -> "25-34 anos"
            age < 45 -> "35-44 anos"
            age < 55 -> "45-54 anos"
            age < 65 -> "55-64 anos"
            else -> "65+ anos"
        }
    }
}

