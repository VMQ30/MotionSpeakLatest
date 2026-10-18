package com.example.motionspeak

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.min

/**
 * Custom FloatingActionButton that renders a continuous Siri-style glowing outline
 * around its circular perimeter.
 *
 * Visual Features:
 *   - Spectrum: Light Blue -> Teal -> Electric Blue -> Electric Violet
 *   - Forever shifting: Continuous 360-degree color rotation
 *   - Faint breathing glow: Soft ambient glow oscillating gently
 *   - 60/120 FPS hardware-accelerated with zero allocations in onDraw
 *   - Idle heartbeat: After [IDLE_BEAT_DELAY_MS] ms of no interaction, the button
 *     plays a lub-dub heartbeat scale animation. The outline scales with the view
 *     as a single unit.
 *
 * Adjust any of the settings in the [Companion] object below to tweak the effect.
 */
class SiriGlowFloatingActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.floatingActionButtonStyle,
) : FloatingActionButton(context, attrs, defStyleAttr) {

    companion object {
        // ═══════════════════════════════════════════════════════════════════════
        // ── TWEAKABLE SETTINGS (Edit these values to customize the effect) ──
        // ═══════════════════════════════════════════════════════════════════════

        /** Width of the crisp core outline on the circular border (in DP) */
        var OUTLINE_WIDTH_DP = 4.5f

        /**
         * Inset of the outline from the colored circle outer edge (in DP).
         * 0.0f = sits directly on the colored circle's circular border.
         * Positive = moves inward into the colored circle.
         * Negative = moves outward.
         */
        var OUTLINE_INSET_DP = 0.0f

        /** Minimum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MIN_WIDTH_DP = 4.0f

        /** Maximum thickness of the ambient glow during breathing (in DP) */
        var GLOW_MAX_WIDTH_DP = 7.0f

        /** Minimum opacity of the faint glow (0.0 = invisible, 1.0 = solid) */
        var GLOW_MIN_ALPHA = 0.30f

        /** Maximum opacity of the faint glow (kept faint and subtle) */
        var GLOW_MAX_ALPHA = 0.70f

        /** Time for the colors to complete one full 360-degree rotation (ms) */
        var COLOR_SHIFT_DURATION_MS = 2700L

        /** Time for one complete inhale/exhale glow breath (ms) */
        var BREATHING_DURATION_MS = 2500L

        // ── Idle heartbeat settings ──────────────────────────────────────────

        /**
         * How long the button must be completely idle (no touch anywhere on the
         * screen) before the heartbeat starts, in milliseconds.
         */
        var IDLE_BEAT_DELAY_MS = 10_000L

        /** Peak scale of the first (stronger) beat in the lub-dub pair */
        var BEAT_SCALE_LUB = 1.10f

        /** Peak scale of the second (softer) beat in the lub-dub pair */
        var BEAT_SCALE_DUB = 1.06f

        /** Duration of each individual scale-up or scale-down in the beat (ms) */
        var BEAT_STEP_MS = 110L

        /** Pause between the lub and the dub (ms) */
        var BEAT_LUB_DUB_GAP_MS = 80L

        /** Rest period between complete lub-dub cycles (ms) */
        var BEAT_REST_MS = 900L

        /** Default fallback spectrum colors (Light Blue -> Teal -> Electric Blue -> Electric Violet) */
        val DEFAULT_SPECTRUM_HEX = intArrayOf(
            0xFF38BDF8.toInt(), // Light Blue
            0xFF2DD4BF.toInt(), // Teal
            0xFF0066FF.toInt(), // Electric Blue
            0xFF8B5CF6.toInt(), // Electric Violet
            0xFF38BDF8.toInt(), // Loop back to Light Blue for seamless sweep
        )

        /** Angular positions of each color in the 360-degree sweep (0.0 to 1.0) */
        val SPECTRUM_POSITIONS = floatArrayOf(
            0.00f,
            0.25f,
            0.50f,
            0.75f,
            1.00f,
        )
        // ═══════════════════════════════════════════════════════════════════════
    }

    private val density = resources.displayMetrics.density

    // Pre-computed pixel values
    private var outlineWidthPx = OUTLINE_WIDTH_DP * density
    private var glowMinWidthPx = GLOW_MIN_WIDTH_DP * density
    private var glowMaxWidthPx = GLOW_MAX_WIDTH_DP * density

    // Paints (allocated once, zero garbage collection in render loop)
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
    private var currentGlowFactor = 0f // 0.0 (min breath) to 1.0 (max breath)

    private var shiftAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null

    // ── Idle heartbeat state ─────────────────────────────────────────────────
    private val idleHandler = Handler(Looper.getMainLooper())
    private var beatAnimator: AnimatorSet? = null
    private var isBeatRunning = false

    private val idleTriggerRunnable = Runnable { startIdleBeat() }

    // Spectrum colors loaded from resources or defaults
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

    private val normalBgColor: Int by lazy {
        try {
            ContextCompat.getColor(context, R.color.ms_brand)
        } catch (_: Exception) {
            0xFF50A9A9.toInt()
        }
    }

    private val scarletColor: Int by lazy {
        try {
            ContextCompat.getColor(context, R.color.ms_scarlet)
        } catch (_: Exception) {
            0xFFDC2626.toInt()
        }
    }

    private val dynamicSpectrumColors = IntArray(5)

    /** Current transition factor from brand color (0.0) to full scarlet red (1.0). */
    var sosTransitionProgress: Float = 0f
        private set

    /**
     * Smoothly transitions the circular button's background and glowing outline
     * to full scarlet red before SOS activates.
     */
    fun setSosTransitionProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        sosTransitionProgress = clamped

        // 1. Blend background circle from normal brand color to full scarlet red
        val blendedBg = ColorUtils.blendARGB(normalBgColor, scarletColor, sosTransitionProgress)
        backgroundTintList = ColorStateList.valueOf(blendedBg)

        // 2. Blend Siri glowing outline towards scarlet red
        updateShaders()
        postInvalidateOnAnimation()
    }

    private fun updateShaders() {
        if (buttonRadius <= 0f) return
        val colors = if (sosTransitionProgress > 0f) {
            for (i in 0 until 4) {
                dynamicSpectrumColors[i] = ColorUtils.blendARGB(spectrumColors[i], scarletColor, sosTransitionProgress)
            }
            dynamicSpectrumColors[4] = dynamicSpectrumColors[0]
            dynamicSpectrumColors
        } else {
            spectrumColors
        }
        sweepGradient = SweepGradient(centerX, centerY, colors, SPECTRUM_POSITIONS)
        coreOutlinePaint.shader = sweepGradient
        outerGlowPaint.shader = sweepGradient
        innerGlowPaint.shader = sweepGradient
    }

    init {
        // Prevent parent or view clipping so the glow can radiate smoothly
        clipToOutline = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Allow faint glow to extend beyond the circular button without clipping
        var p = parent
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
        }
        startAnimations()
        scheduleIdleBeat()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        cancelIdleBeat()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startAnimations()
            scheduleIdleBeat()
        } else {
            stopAnimations()
            cancelIdleBeat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // In FloatingActionButton, paddingLeft/paddingTop is used internally to center the
        // icon (hand image). To draw the outline on the COLORED CIRCLE itself, we use the
        // full width and height of the button rather than subtracting icon padding.
        centerX = w / 2f
        centerY = h / 2f
        buttonRadius = min(w, h) / 2f

        if (buttonRadius > 0f) {
            updateShaders()
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

    // ── Idle heartbeat public API ─────────────────────────────────────────────

    /**
     * Called by the host activity (via [BaseActivity.dispatchTouchEvent]) whenever
     * the screen is touched. Stops any running beat and restarts the idle countdown.
     */
    fun onUserInteraction() {
        stopIdleBeat()
        scheduleIdleBeat()
    }

    /**
     * Arms the idle countdown. After [IDLE_BEAT_DELAY_MS] ms with no call to
     * [onUserInteraction], the heartbeat starts.
     */
    fun scheduleIdleBeat() {
        idleHandler.removeCallbacks(idleTriggerRunnable)
        idleHandler.postDelayed(idleTriggerRunnable, IDLE_BEAT_DELAY_MS)
    }

    /** Cancels both the countdown and any running beat animation. */
    fun cancelIdleBeat() {
        idleHandler.removeCallbacks(idleTriggerRunnable)
        stopIdleBeat()
    }

    /** Starts the continuous lub-dub heartbeat scale animation. */
    private fun startIdleBeat() {
        if (isBeatRunning) return
        isBeatRunning = true
        playBeatCycle()
    }

    /** Stops the heartbeat and snaps the button back to normal scale. */
    private fun stopIdleBeat() {
        isBeatRunning = false
        beatAnimator?.cancel()
        beatAnimator = null
        // Snap back to 1:1 with a short ease so it doesn't jump
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Plays one lub-dub cycle, then schedules itself again after [BEAT_REST_MS]
     * to create a continuous heartbeat until [stopIdleBeat] is called.
     *
     * The animation drives scaleX AND scaleY together, so the SweepGradient
     * outline (drawn in [draw] in the view's own coordinate space) scales with
     * the button body perfectly — they move as a single unit.
     */
    private fun playBeatCycle() {
        if (!isBeatRunning) return

        val step = BEAT_STEP_MS
        val decel = DecelerateInterpolator(1.5f)

        // Lub: scale up to LUB peak then back to 1
        val lubUp = scaleAnimPair(1f, BEAT_SCALE_LUB, step)
        val lubDown = scaleAnimPair(BEAT_SCALE_LUB, 1f, step)

        // Dub: scale up to DUB peak then back to 1  (slightly softer)
        val dubUp = scaleAnimPair(1f, BEAT_SCALE_DUB, step)
        val dubDown = scaleAnimPair(BEAT_SCALE_DUB, 1f, step)

        val set = AnimatorSet().apply {
            playSequentially(lubUp, lubDown, dubUp, dubDown)
        }

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (isBeatRunning) {
                    // Wait for the rest gap, then play the next cycle
                    idleHandler.postDelayed({ playBeatCycle() }, BEAT_REST_MS)
                }
            }
        })

        beatAnimator = set
        set.start()
    }

    /**
     * Creates a paired [AnimatorSet] that animates both [scaleX] and [scaleY]
     * from [from] to [to] over [durationMs] simultaneously — keeping the
     * button and its outline in perfect sync.
     */
    private fun scaleAnimPair(from: Float, to: Float, durationMs: Long): AnimatorSet {
        val sx = ObjectAnimator.ofFloat(this, SCALE_X, from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
        }
        val sy = ObjectAnimator.ofFloat(this, SCALE_Y, from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
        }
        return AnimatorSet().apply { playTogether(sx, sy) }
    }

    override fun draw(canvas: Canvas) {
        // 1. Draw FAB background, hand icon, and ripple
        super.draw(canvas)

        // 2. Draw Siri color-shifting glowing outline on top
        if (buttonRadius <= 0f || sweepGradient == null) return

        // Rotate the spectrum shader around the button's center
        shaderMatrix.setRotate(currentRotationAngle, centerX, centerY)
        sweepGradient?.setLocalMatrix(shaderMatrix)

        // Current breathing glow parameters
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
