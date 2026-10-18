package com.example.motionspeak

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Custom MaterialButton that renders a continuous Siri-style shifted linear gradient
 * across the button surface and around its perimeter with a soft breathing glow.
 *
 * Used for the "Open camera" button on the home page.
 *
 * Visual Features:
 *   - Gradient type: Shifted linear gradient at a dynamic angle (not radial)
 *   - Spectrum: Light Blue -> Teal -> Electric Blue -> Electric Violet
 *   - Forever shifting: Continuous diagonal linear flow
 *   - Faint breathing glow: Soft ambient glow oscillating gently along the rounded rect border
 *   - 60/120 FPS hardware-accelerated with zero allocations in onDraw
 *
 * Adjust any of the settings in the [Companion] object below to tweak the effect.
 */
class SiriLinearGradientButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    companion object {
        // ═══════════════════════════════════════════════════════════════════════
        // ── TWEAKABLE SETTINGS (Edit these values to customize the effect) ──
        // ═══════════════════════════════════════════════════════════════════════

        /** Angle of the linear gradient in degrees (e.g. 25° for dynamic diagonal flow) */
        var GRADIENT_ANGLE_DEG = 25.0

        /** Width of the crisp core outline on the button border (in DP) */
        var OUTLINE_WIDTH_DP = 1.8f

        /** Minimum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MIN_WIDTH_DP = 2.5f

        /** Maximum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MAX_WIDTH_DP = 5.5f

        /** Minimum opacity of the faint glow (0.0 = invisible, 1.0 = solid) */
        var GLOW_MIN_ALPHA = 0.25f

        /** Maximum opacity of the faint glow (kept subtle and faint) */
        var GLOW_MAX_ALPHA = 0.55f

        /** Multiplier for how spread out the gradient colors are (higher = more gradual, spread out) */
        var SPREAD_FACTOR = 2.8f

        /** Time for the linear gradient to complete one full cycle shift (ms) */
        var COLOR_SHIFT_DURATION_MS = 4000L

        /** Time for one complete inhale/exhale glow breath (ms) */
        var BREATHING_DURATION_MS = 2500L

        /** Default fallback spectrum colors (Light Blue -> Teal -> Electric Blue -> Electric Violet) */
        val DEFAULT_SPECTRUM_HEX = intArrayOf(
            0xFF38BDF8.toInt(), // Light Blue
            0xFF2DD4BF.toInt(), // Teal
            0xFF0066FF.toInt(), // Electric Blue
            0xFF8B5CF6.toInt(), // Electric Violet
            0xFF38BDF8.toInt(), // Loop back to Light Blue for seamless sweep
        )
        // ═══════════════════════════════════════════════════════════════════════
    }

    private val density = resources.displayMetrics.density

    // Pre-computed pixel values
    private var outlineWidthPx = OUTLINE_WIDTH_DP * density
    private var glowMinWidthPx = GLOW_MIN_WIDTH_DP * density
    private var glowMaxWidthPx = GLOW_MAX_WIDTH_DP * density

    // Paints (allocated once, zero garbage collection in render loop)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

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
    private val bodyRect = RectF()
    private val outlineRect = RectF()
    private val shaderMatrix = Matrix()

    private var linearGradient: LinearGradient? = null
    private var cycleLength = 0f
    private var cosAngle = 0f
    private var sinAngle = 0f

    // Animation state
    private var currentShiftProgress = 0f
    private var currentGlowFactor = 0f

    private var shiftAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null

    // Spectrum colors loaded from resources or defaults
    private val spectrumColors: IntArray by lazy {
        try {
            val c1 = ContextCompat.getColor(context, R.color.ms_siri_light_blue)
            val c2 = ContextCompat.getColor(context, R.color.ms_siri_teal)
            val c3 = ContextCompat.getColor(context, R.color.ms_siri_electric_blue)
            val c4 = ContextCompat.getColor(context, R.color.ms_siri_electric_violet)
            // Repeating cycle for seamless infinite wrap:
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

    init {
        clipToOutline = false
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
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
        if (w <= 0 || h <= 0) return

        val rad = Math.toRadians(GRADIENT_ANGLE_DEG)
        cosAngle = cos(rad).toFloat()
        sinAngle = sin(rad).toFloat()

        val diagonal = hypot(w.toFloat(), h.toFloat())
        cycleLength = max(w.toFloat(), diagonal) * SPREAD_FACTOR
        val totalSpan = cycleLength * 2f

        val x0 = 0f
        val y0 = 0f
        val x1 = totalSpan * cosAngle
        val y1 = totalSpan * sinAngle

        linearGradient = LinearGradient(
            x0, y0, x1, y1,
            spectrumColors,
            spectrumPositions,
            Shader.TileMode.REPEAT
        )

        fillPaint.shader = linearGradient
        coreOutlinePaint.shader = linearGradient
        outerGlowPaint.shader = linearGradient
        innerGlowPaint.shader = linearGradient
    }

    private fun startAnimations() {
        if (shiftAnimator == null) {
            shiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COLOR_SHIFT_DURATION_MS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { anim ->
                    currentShiftProgress = anim.animatedValue as Float
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

    private fun updateShaderMatrix() {
        if (cycleLength <= 0f || linearGradient == null) return
        val offset = currentShiftProgress * cycleLength
        shaderMatrix.reset()
        shaderMatrix.setTranslate(-offset * cosAngle, -offset * sinAngle)
        linearGradient?.setLocalMatrix(shaderMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f && linearGradient != null) {
            updateShaderMatrix()

            bodyRect.set(0f, 0f, w, h)
            val radius = if (cornerRadius > 0) cornerRadius.toFloat() else 8f * density
            canvas.drawRoundRect(bodyRect, radius, radius, fillPaint)
        }
        super.onDraw(canvas)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || linearGradient == null) return

        updateShaderMatrix()

        val currentGlowAlpha = GLOW_MIN_ALPHA + (GLOW_MAX_ALPHA - GLOW_MIN_ALPHA) * currentGlowFactor
        val currentGlowWidth = glowMinWidthPx + (glowMaxWidthPx - glowMinWidthPx) * currentGlowFactor

        val inset = outlineWidthPx / 2f
        outlineRect.set(inset, inset, w - inset, h - inset)
        val radius = if (cornerRadius > 0) cornerRadius.toFloat() else 8f * density
        val strokeRadius = max(0f, radius - inset)

        // Layer 1: Ambient outer faint glow
        outerGlowPaint.strokeWidth = currentGlowWidth
        outerGlowPaint.alpha = (currentGlowAlpha * 0.40f * 255f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(outlineRect, strokeRadius, strokeRadius, outerGlowPaint)

        // Layer 2: Mid-halo glow for smooth optical falloff
        innerGlowPaint.strokeWidth = currentGlowWidth * 0.65f
        innerGlowPaint.alpha = (currentGlowAlpha * 0.70f * 255f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(outlineRect, strokeRadius, strokeRadius, innerGlowPaint)

        // Layer 3: Crisp core outline along the rounded rectangle border
        coreOutlinePaint.strokeWidth = outlineWidthPx
        coreOutlinePaint.alpha = 240
        canvas.drawRoundRect(outlineRect, strokeRadius, strokeRadius, coreOutlinePaint)
    }
}
