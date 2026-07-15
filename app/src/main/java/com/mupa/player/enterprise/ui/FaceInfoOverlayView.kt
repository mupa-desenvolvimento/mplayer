package com.mupa.player.enterprise.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mupa.player.enterprise.audience.DetectedFace

class FaceInfoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var faces: List<DetectedFace> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(220, 0, 220, 120)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(170, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val rect = RectF()

    fun update(detected: List<DetectedFace>) {
        faces = detected
        invalidate()
    }

    fun currentFaces(): List<DetectedFace> = faces

    override fun onDraw(canvas: Canvas) {
        if (faces.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        textPaint.textSize = (w * 0.050f).coerceIn(28f, 68f)
        val lineH = textPaint.textSize * 1.35f
        val pad = textPaint.textSize * 0.4f

        for (face in faces) {
            if (face.boxRight <= face.boxLeft || face.boxBottom <= face.boxTop) continue

            // Mirror X for front camera (face-api works on non-mirrored frame,
            // but PreviewView shows mirrored image for front camera).
            val left  = (1f - face.boxRight) * w
            val right = (1f - face.boxLeft)  * w
            val top    = face.boxTop    * h
            val bottom = face.boxBottom * h

            rect.set(left, top, right, bottom)
            canvas.drawRect(rect, boxPaint)

            val lines = buildLines(face)
            if (lines.isEmpty()) continue

            val labelW = lines.maxOf { textPaint.measureText(it) } + pad * 2
            val labelH = lines.size * lineH + pad * 2

            val labelTop = (top - labelH - 6f).coerceAtLeast(0f)
            val labelLeft = left.coerceAtMost(w - labelW)
            rect.set(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH)
            canvas.drawRoundRect(rect, 12f, 12f, bgPaint)

            for ((i, line) in lines.withIndex()) {
                canvas.drawText(
                    line,
                    labelLeft + pad,
                    labelTop + pad + textPaint.textSize + i * lineH,
                    textPaint,
                )
            }
        }
    }

    private fun buildLines(face: DetectedFace): List<String> {
        val parts = mutableListOf<String>()

        val ageLabel =
            face.ageRange?.takeIf { it.isNotBlank() }
                ?: face.estimatedAge?.let { estimatedAgeToBucket(it) }
                ?: "N/D"
        parts += "Idade: $ageLabel"

        val genderLabel =
            face.gender?.takeIf { it.isNotBlank() }?.let {
                when (it.lowercase()) {
                    "male" -> "Masculino"
                    "female" -> "Feminino"
                    else -> it
                }
            } ?: "N/D"
        parts += "Gênero: $genderLabel"

        val emotionLabel = face.emotion?.takeIf { it.isNotBlank() } ?: "N/D"
        parts += "Emoção: $emotionLabel"

        return parts
    }

    private fun estimatedAgeToBucket(age: Int): String {
        return when {
            age < 13 -> "Criança"
            age < 18 -> "Adolescente"
            age < 30 -> "Jovem Adulto"
            age < 60 -> "Adulto"
            else -> "Sênior"
        }
    }
}
