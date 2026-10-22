package com.example.motionspeak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Custom detection meter bar that continuously shifts through the Siri spectrum
 * (Light Blue -> Teal -> Electric Blue -> Electric Violet) identical to the homepage
 * "Open camera" Siri button.
 */
class SiriDetectionMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SHIFT_DURATION_MS = 3500L
        private const val PROGRESS_ANIM_DURATION_MS = 140L
        private const val GRADIENT_ANGLE_DEG = 15.0

        val DEFAULT_SPECTRUM_HEX = intArrayOf(
            0xFF38BDF8.toInt(), // Light Blue
            0xFF2DD4BF.toInt(), // Teal
            0xFF0066FF.toInt(), // Electric Blue
            0xFF8B5CF6.toInt(), // Electric Violet
            0xFF38BDF8.toInt()  // Seamless loop back
        )
    }

    private val density = resources.displayMetrics.density

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 255, 255)
    }

    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(40, 255, 255, 255)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    // Geometry
    private val trackRect = RectF()
    private val trackPath = Path()
    private val fillRect = RectF()
    private val shaderMatrix = Matrix()

    private var linearGradient: LinearGradient? = null
    private var cycleLength = 0f
    private var cosAngle = 0f
    private var sinAngle = 0f

    // Progress values (0..100)
    var progress: Int = 0
        private set
    private var displayProgress: Float = 0f

    // Animations
    private var currentShiftProgress = 0f
    private var shiftAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    // Spectrum colors matching SiriLinearGradientButton
    private val spectrumColors: IntArray by lazy {
        try {
            val c1 = ContextCompat.getColor(context, R.color.ms_siri_light_blue)
            val c2 = ContextCompat.getColor(context, R.color.ms_siri_teal)
            val c3 = ContextCompat.getColor(context, R.color.ms_siri_electric_blue)
            val c4 = ContextCompat.getColor(context, R.color.ms_siri_electric_violet)
            intArrayOf(c1, c2, c3, c4, c1, c2, c3, c4, c1)
        } catch (_: Exception) {
            intArrayOf(
                DEFAULT_SPECTRUM_HEX[0],
                DEFAULT_SPECTRUM_HEX[1],
                DEFAULT_SPECTRUM_HEX[2],
                DEFAULT_SPECTRUM_HEX[3],
                DEFAULT_SPECTRUM_HEX[0],
                DEFAULT_SPECTRUM_HEX[1],
                DEFAULT_SPECTRUM_HEX[2],
                DEFAULT_SPECTRUM_HEX[3],
                DEFAULT_SPECTRUM_HEX[0]
            )
        }
    }

    private val spectrumPositions = floatArrayOf(
        0.000f, 0.125f, 0.250f, 0.375f, 0.500f, 0.625f, 0.750f, 0.875f, 1.000f
    )

    fun setProgress(value: Int, animate: Boolean = true) {
        val clamped = value.coerceIn(0, 100)
        if (clamped == progress) return
        progress = clamped

        progressAnimator?.cancel()
        if (animate && isAttachedToWindow && width > 0) {
            progressAnimator = ValueAnimator.ofFloat(displayProgress, clamped.toFloat()).apply {
                duration = PROGRESS_ANIM_DURATION_MS
                addUpdateListener { anim ->
                    displayProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            displayProgress = clamped.toFloat()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (shiftAnimator == null) {
            shiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SHIFTIFT()
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { anim ->
                    currentShiftProgress = anim.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
        }
        if (shiftAnimator?.isStarted != true) {
            shiftAnimator?.start()
        }
    }

    private fun SHIFTIFT(): Long = SHIFT_DURATION_MS

    private fun stopAnimation() {
        shiftAnimator?.cancel()
        progressAnimator?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val radius = h / 2f
        trackRect.set(0f, 0f, w.toFloat(), h.toFloat())
        trackPath.reset()
        trackPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW)

        val rad = Math.toRadians(GRADIENT_ANGLE_DEG)
        cosAngle = cos(rad).toFloat()
        sinAngle = sin(rad).toFloat()

        val diagonal = hypot(w.toFloat(), h.toFloat())
        cycleLength = max(w.toFloat(), diagonal) * 1.5f
        val totalSpan = cycleLength * 2f

        linearGradient = LinearGradient(
            0f, 0f, totalSpan * cosAngle, totalSpan * sinAngle,
            spectrumColors,
            spectrumPositions,
            Shader.TileMode.REPEAT
        )
        fillPaint.shader = linearGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f

        // Draw track background and subtle border
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        canvas.drawRoundRect(trackRect, radius, radius, trackBorderPaint)

        // Draw animated gradient progress
        val fillWidth = (displayProgress / 100f) * w
        if (fillWidth > 0f && linearGradient != null) {
            val offset = currentShiftProgress * cycleLength
            shaderMatrix.reset()
            shaderMatrix.setTranslate(-offset * cosAngle, -offset * sinAngle)
            linearGradient?.setLocalMatrix(shaderMatrix)

            canvas.save()
            canvas.clipPath(trackPath)
            fillRect.set(0f, 0f, fillWidth, h)
            canvas.drawRect(fillRect, fillPaint)
            canvas.restore()
        }
    }
}
