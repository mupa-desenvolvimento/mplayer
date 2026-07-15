package com.mupa.engage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var fingers: Int = -1  // -1 = no detection yet
    private var hands: List<List<Pair<Float, Float>>> = emptyList()

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val handLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(230, 0, 220, 120)
    }
    private val handPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 0, 220, 120)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    fun updateFingers(f: Int) {
        if (f == fingers) return
        fingers = f
        invalidate()
    }

    fun updateHands(h: List<List<Pair<Float, Float>>>) {
        hands = h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawHands(canvas)
        if (fingers <= 0) return

        val cx = width * 0.82f
        val cy = height * 0.18f
        val r = width * 0.09f

        circlePaint.color = when (fingers) {
            1 -> Color.argb(180, 0, 180, 90)
            2 -> Color.argb(180, 220, 60, 60)
            else -> Color.argb(160, 120, 120, 120)
        }
        canvas.drawCircle(cx, cy, r, circlePaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        val emoji = when (fingers) {
            1 -> "☝️"
            2 -> "✌️"
            else -> fingers.toString()
        }
        textPaint.textSize = r * 1.1f
        canvas.drawText(emoji, cx, cy + textPaint.textSize * 0.38f, textPaint)
    }

    private fun drawHands(canvas: Canvas) {
        if (hands.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pointR = (w * 0.010f).coerceIn(6f, 14f)

        for (hand in hands) {
            if (hand.size < 21) continue

            fun p(i: Int): Pair<Float, Float> = hand[i]
            fun drawLine(a: Int, b: Int) {
                val pa = p(a); val pb = p(b)
                canvas.drawLine(pa.first * w, pa.second * h, pb.first * w, pb.second * h, handLinePaint)
            }

            val connections = arrayOf(
                intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
                intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
                intArrayOf(0, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
                intArrayOf(0, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
                intArrayOf(0, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
                intArrayOf(5, 9), intArrayOf(9, 13), intArrayOf(13, 17),
            )
            for (c in connections) drawLine(c[0], c[1])

            for (pt in hand) {
                canvas.drawCircle(pt.first * w, pt.second * h, pointR, handPointPaint)
            }
        }
    }
}
