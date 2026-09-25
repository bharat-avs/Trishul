package com.example.trishul

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class LiveGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2C3D")
        strokeWidth = 1f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val points = mutableListOf<Float>()
    private val maxPoints = 35

    init {
        for (i in 0 until maxPoints) points.add(24.0f)
    }

    fun addTelemetry(tempC: Float) {
        if (points.size >= maxPoints) points.removeAt(0)
        points.add(tempC)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 1..4) {
            val y = (h / 5) * i
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val stepX = w / (maxPoints - 1)
        val minTemp = 15f
        val maxTemp = 40f

        val path = Path()
        val fillPath = Path()

        for (i in points.indices) {
            val normalized = (points[i] - minTemp) / (maxTemp - minTemp)
            val y = h - (normalized.coerceIn(0.05f, 0.95f) * h)
            val x = i * stepX

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((points.size - 1) * stepX, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#3300E5FF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}