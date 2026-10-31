package com.example.motionspeak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated Siri-style glowing gradient border for the dialogue box
 * when text has been turned into a synthesized sentence.
 */
class SiriGradientBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isDither = true
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.5f * density
        alpha = 90
        isDither = true
    }

    // Spectrum: Light Blue -> Teal -> Electric Blue -> Electric Violet -> Hot Pink -> Loop
    private val colors = intArrayOf(
        0xFF38BDF8.toInt(), // Light Blue
        0xFF2DD4BF.toInt(), // Teal
        0xFF0066FF.toInt(), // Electric Blue
        0xFF8B5CF6.toInt(), // Electric Violet
        0xFFEC4899.toInt(), // Hot Pink
        0xFFF59E0B.toInt(), // Amber
        0xFF38BDF8.toInt()  // Loop back
    )

    private val positions = floatArrayOf(0.0f, 0.18f, 0.36f, 0.54f, 0.72f, 0.90f, 1.0f)
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private val cornerRadius = 14f * density
    private var rotationAngle = 0f

    private val rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    fun startAnimation() {
        if (!rotateAnimator.isRunning) {
            rotateAnimator.start()
        }
        invalidate()
    }

    fun stopAnimation() {
        if (rotateAnimator.isRunning) {
            rotateAnimator.cancel()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokePaint.strokeWidth / 2f + 1f * density
        rect.set(inset, inset, w.toFloat() - inset, h.toFloat() - inset)
        val sweep = SweepGradient(w / 2f, h / 2f, colors, positions)
        strokePaint.shader = sweep
        glowPaint.shader = sweep
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        shaderMatrix.setRotate(rotationAngle, width / 2f, height / 2f)
        strokePaint.shader?.setLocalMatrix(shaderMatrix)
        glowPaint.shader?.setLocalMatrix(shaderMatrix)

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
