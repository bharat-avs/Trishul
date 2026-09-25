package com.example.trishul

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class TacticalGlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        alpha = 130
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A3B4C")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private var rotationAngle = 0f
    private val sweepShaderMatrix = Matrix()
    private var sweepAngle = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.82f

        // Outer Range Rings
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.5f, ringPaint)

        // Crosshairs & Cardinal Labels
        canvas.drawLine(cx - radius - 15, cy, cx + radius + 15, cy, ringPaint)
        canvas.drawLine(cx, cy - radius - 15, cx, cy + radius + 15, ringPaint)
        canvas.drawText("N 000°", cx - 35, cy - radius + 25, textPaint)
        canvas.drawText("NODE_A [LOCK]", cx + radius * 0.25f, cy - radius * 0.35f, textPaint)

        // 3D Rotating Wireframe Longitudes & Latitudes
        rotationAngle += 0.015f
        if (rotationAngle > 2 * PI) rotationAngle = 0f

        val latLines = 6
        for (i in 1..latLines) {
            val latRad = (i.toFloat() / (latLines + 1) - 0.5f) * PI.toFloat()
            val y = cy + radius * sin(latRad)
            val rx = radius * cos(latRad)
            val rect = RectF(cx - rx, y - (radius * 0.08f), cx + rx, y + (radius * 0.08f))
            canvas.drawOval(rect, gridPaint)
        }

        val lonLines = 8
        for (i in 0 until lonLines) {
            val angle = rotationAngle + (i * PI / lonLines).toFloat()
            val scaleX = cos(angle)
            val rect = RectF(cx - radius * abs(scaleX), cy - radius, cx + radius * abs(scaleX), cy + radius)
            gridPaint.alpha = (abs(scaleX) * 160 + 40).toInt()
            canvas.drawOval(rect, gridPaint)
        }

        // Tactical Radar Sweep
        sweepAngle = (sweepAngle + 2.5f) % 360f
        sweepShaderMatrix.setRotate(sweepAngle, cx, cy)
        val sweepGradient = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#1500E5FF"), Color.parseColor("#6600E5FF")),
            floatArrayOf(0.0f, 0.75f, 1.0f)
        )
        sweepGradient.setLocalMatrix(sweepShaderMatrix)
        val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = sweepGradient
        }
        canvas.drawCircle(cx, cy, radius, sweepPaint)

        // Blinking Mesh Node Targets
        val blinkAlpha = ((sin(System.currentTimeMillis() / 200.0) + 1.0) * 127).toInt()
        targetPaint.alpha = max(60, blinkAlpha)
        canvas.drawCircle(cx + radius * 0.4f, cy - radius * 0.3f, 7f, targetPaint)
        canvas.drawCircle(cx - radius * 0.35f, cy + radius * 0.25f, 5f, targetPaint)

        postInvalidateOnAnimation()
    }
}