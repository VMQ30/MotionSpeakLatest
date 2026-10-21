package com.example.motionspeak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.min

/**
 * Custom MaterialButton that renders a continuous Siri-style glowing outline
 * around its circular perimeter. Used for the circular Sign button on the camera screen.
 *
 * Visual Features:
 *   - Spectrum: Light Blue -> Teal -> Electric Blue -> Electric Violet
 *   - Forever shifting: Continuous 360-degree color rotation
 *   - Faint breathing glow: Soft ambient glow oscillating gently
 *   - 60/120 FPS hardware-accelerated with zero allocations in onDraw
 */
class SiriGlowMaterialButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    companion object {
        /** Width of the crisp core outline on the circular border (in DP) */
        var OUTLINE_WIDTH_DP = 4.5f

        /** Inset of the outline from the circular edge (in DP) */
        var OUTLINE_INSET_DP = 0.0f

        /** Minimum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MIN_WIDTH_DP = 4.0f

        /** Maximum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MAX_WIDTH_DP = 7.0f

        /** Minimum opacity of the faint glow (0.0 = invisible, 1.0 = solid) */
        var GLOW_MIN_ALPHA = 0.30f

        /** Maximum opacity of the faint glow */
        var GLOW_MAX_ALPHA = 0.70f

        /** Time for the colors to complete one full 360-degree rotation (ms) */
        var COLOR_SHIFT_DURATION_MS = 2700L

        /** Time for one complete inhale/exhale glow breath (ms) */
        var BREATHING_DURATION_MS = 2500L

        /** Default fallback spectrum colors (Light Blue -> Teal -> Electric Blue -> Electric Violet) */
        val DEFAULT_SPECTRUM_HEX = intArrayOf(
            0xFF38BDF8.toInt(), // Light Blue
            0xFF2DD4BF.toInt(), // Teal
            0xFF0066FF.toInt(), // Electric Blue
            0xFF8B5CF6.toInt(), // Electric Violet
            0xFF38BDF8.toInt(), // Loop back to Light Blue
        )

        /** Angular positions of each color in the 360-degree sweep (0.0 to 1.0) */
        val SPECTRUM_POSITIONS = floatArrayOf(
            0.00f,
            0.25f,
            0.50f,
            0.75f,
            1.00f,
        )
    }

    private val density = resources.displayMetrics.density

    // Pre-computed pixel values
    private var outlineWidthPx = OUTLINE_WIDTH_DP * density
    private var glowMinWidthPx = GLOW_MIN_WIDTH_DP * density
    private var glowMaxWidthPx = GLOW_MAX_WIDTH_DP * density

    // Paints
    private val coreOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    // Geometry & Shaders
    private var centerX = 0f
    private var centerY = 0f
    private var buttonRadius = 0f
    private var sweepGradient: SweepGradient? = null
    private val shaderMatrix = Matrix()

    // Animation state
    private var currentRotationAngle = 0f
    private var currentGlowFactor = 0f

    private var shiftAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null

    private val spectrumColors: IntArray by lazy {
        try {
            val c1 = ContextCompat.getColor(context, R.color.ms_siri_light_blue)
            val c2 = ContextCompat.getColor(context, R.color.ms_siri_teal)
            val c3 = ContextCompat.getColor(context, R.color.ms_siri_electric_blue)
            val c4 = ContextCompat.getColor(context, R.color.ms_siri_electric_violet)
            intArrayOf(c1, c2, c3, c4, c1)
        } catch (_: Exception) {
            DEFAULT_SPECTRUM_HEX
        }
    }

    init {
        clipToOutline = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        var p = parent
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
        }
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startAnimations()
        } else {
            stopAnimations()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        buttonRadius = min(w, h) / 2f

        if (buttonRadius > 0f) {
            sweepGradient = SweepGradient(centerX, centerY, spectrumColors, SPECTRUM_POSITIONS)
            coreOutlinePaint.shader = sweepGradient
            outerGlowPaint.shader = sweepGradient
            innerGlowPaint.shader = sweepGradient
        }
    }

    private fun startAnimations() {
        if (shiftAnimator == null) {
            shiftAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = COLOR_SHIFT_DURATION_MS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { anim ->
                    currentRotationAngle = anim.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
        }
        if (shiftAnimator?.isStarted != true) shiftAnimator?.start()

        if (breathAnimator == null) {
            breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BREATHING_DURATION_MS
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { anim ->
                    currentGlowFactor = anim.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
        }
        if (breathAnimator?.isStarted != true) breathAnimator?.start()
    }

    private fun stopAnimations() {
        shiftAnimator?.cancel()
        breathAnimator?.cancel()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (buttonRadius <= 0f || sweepGradient == null) return

        shaderMatrix.setRotate(currentRotationAngle, centerX, centerY)
        sweepGradient?.setLocalMatrix(shaderMatrix)

        val currentGlowAlpha = GLOW_MIN_ALPHA + (GLOW_MAX_ALPHA - GLOW_MIN_ALPHA) * currentGlowFactor
        val currentGlowWidth = glowMinWidthPx + (glowMaxWidthPx - glowMinWidthPx) * currentGlowFactor

        val rimRadius = buttonRadius - (outlineWidthPx / 2f) - (OUTLINE_INSET_DP * density)

        // Layer 1: Ambient outer faint glow
        outerGlowPaint.strokeWidth = currentGlowWidth
        outerGlowPaint.alpha = (currentGlowAlpha * 0.40f * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, rimRadius, outerGlowPaint)

        // Layer 2: Mid-halo glow for smooth optical falloff
        innerGlowPaint.strokeWidth = currentGlowWidth * 0.65f
        innerGlowPaint.alpha = (currentGlowAlpha * 0.70f * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, rimRadius, innerGlowPaint)

        // Layer 3: Crisp Siri core outline along the circular border
        coreOutlinePaint.strokeWidth = outlineWidthPx
        coreOutlinePaint.alpha = 240
        canvas.drawCircle(centerX, centerY, rimRadius, coreOutlinePaint)
    }
}
