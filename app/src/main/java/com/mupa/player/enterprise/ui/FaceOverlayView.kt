package com.mupa.player.enterprise.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Material green
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.parseColor("#804CAF50") // Semi-transparent green
        style = Paint.Style.FILL
    }

    private var faces: List<DrawFace> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    data class DrawFace(
        val rect: Rect,
        val label: String
    )

    fun updateFaces(newFaces: List<DrawFace>, srcWidth: Int, srcHeight: Int) {
        this.faces = newFaces
        this.sourceWidth = if (srcWidth > 0) srcWidth else 1
        this.sourceHeight = if (srcHeight > 0) srcHeight else 1
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty()) return

        val viewWidth = width
        val viewHeight = height

        // Scale factors to map frame coordinate system to View dimensions
        val scaleX = viewWidth.toFloat() / sourceWidth.toFloat()
        val scaleY = viewHeight.toFloat() / sourceHeight.toFloat()

        for (face in faces) {
            // Front camera preview is mirrored. We invert the mapped X coordinates.
            val left = viewWidth - (face.rect.right * scaleX)
            val right = viewWidth - (face.rect.left * scaleX)
            val top = face.rect.top * scaleY
            val bottom = face.rect.bottom * scaleY

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Label positioning (above bounding box)
            val text = face.label
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize
            val padding = 12f
            
            val labelLeft = left
            val labelTop = (top - textHeight - (padding * 2)).coerceAtLeast(0f)
            val labelRight = (left + textWidth + (padding * 2)).coerceAtMost(viewWidth.toFloat())
            val labelBottom = top.coerceAtLeast(0f)

            // Draw tag background
            canvas.drawRect(labelLeft, labelTop, labelRight, labelBottom, textBackgroundPaint)

            // Draw tag text
            canvas.drawText(
                text,
                left + padding,
                labelBottom - padding,
                textPaint
            )
        }
    }
}
