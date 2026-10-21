package com.example.motionspeak

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Tailed tooltip speech-bubble view pointing downward at the center navigation FAB.
 * Displays "click here to open the camera" with high-contrast styling and an electric
 * Siri light blue outline matching the circular button's shifting glow.
 */
class CameraTooltipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    val tailWidthPx = 18f * density
    val tailHeightPx = 10f * density
    val cornerRadiusPx = 12f * density

    // Deep slate/navy bubble fill (#0F172A)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F172A")
    }

    // High-contrast electric Siri light blue outline matching the circular button
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#38BDF8")
    }

    private val path = Path()
    private val arcRect = RectF()

    var tailCenterX: Float? = null
        set(value) {
            field = value
            postInvalidate()
        }

    val textView: TextView
    private var bobAnimator: ObjectAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        clipToOutline = false
        clipChildren = false
        elevation = 30f * density

        val font = try {
            ResourcesCompat.getFont(context, R.font.poppins_semibold)
        } catch (_: Exception) {
            null
        }

        textView = TextView(context).apply {
            text = "click here to open the camera"
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            typeface = font ?: Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(
                (16 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                (10 * density + tailHeightPx).toInt()
            )
        }

        addView(
            textView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bodyBottom = h - tailHeightPx
        val strokeInset = strokePaint.strokeWidth / 2f
        val r = cornerRadiusPx
        val halfTail = tailWidthPx / 2f
        val minTailX = strokeInset + r + halfTail
        val maxTailX = w - strokeInset - r - halfTail
        val centerX = if (minTailX <= maxTailX) {
            (tailCenterX ?: (w / 2f)).coerceIn(minTailX, maxTailX)
        } else {
            w / 2f
        }

        path.reset()
        // 1. Top-left arc: 180° to 270°
        arcRect.set(strokeInset, strokeInset, strokeInset + 2 * r, strokeInset + 2 * r)
        path.arcTo(arcRect, 180f, 90f)

        // 2. Top edge
        path.lineTo(w - strokeInset - r, strokeInset)

        // 3. Top-right arc: 270° to 360° (0°)
        arcRect.set(w - strokeInset - 2 * r, strokeInset, w - strokeInset, strokeInset + 2 * r)
        path.arcTo(arcRect, 270f, 90f)

        // 4. Right edge
        path.lineTo(w - strokeInset, bodyBottom - r)

        // 5. Bottom-right arc: 0° to 90°
        arcRect.set(w - strokeInset - 2 * r, bodyBottom - 2 * r, w - strokeInset, bodyBottom)
        path.arcTo(arcRect, 0f, 90f)

        // 6. Right bottom segment before tail
        path.lineTo(centerX + halfTail, bodyBottom)

        // 7. Tail pointing downward at the hand sign button
        path.lineTo(centerX, h - strokeInset)
        path.lineTo(centerX - halfTail, bodyBottom)

        // 8. Left bottom segment after tail
        path.lineTo(strokeInset + r, bodyBottom)

        // 9. Bottom-left arc: 90° to 180°
        arcRect.set(strokeInset, bodyBottom - 2 * r, strokeInset + 2 * r, bodyBottom)
        path.arcTo(arcRect, 90f, 90f)

        // 10. Left edge up to top-left arc
        path.lineTo(strokeInset, strokeInset + r)
        path.close()

        canvas.drawPath(path, bubblePaint)
        canvas.drawPath(path, strokePaint)

        super.onDraw(canvas)
    }

    fun showWithAnimation() {
        // Stay visible by default so animations never hide the view if disabled/delayed
        alpha = 1f
        scaleX = 0.85f
        scaleY = 0.85f
        post {
            pivotX = tailCenterX ?: (width / 2f)
            pivotY = height.toFloat()

            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .withEndAction {
                    startBobbing()
                }
                .start()
        }
    }

    private fun startBobbing() {
        stopBobbing()
        bobAnimator = ObjectAnimator.ofFloat(this, "translationY", 0f, -5f * density).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBobbing() {
        bobAnimator?.cancel()
        bobAnimator = null
    }

    fun dismissWithAnimation() {
        stopBobbing()
        animate()
            .alpha(0f)
            .scaleX(0.75f)
            .scaleY(0.75f)
            .setDuration(200L)
            .withEndAction {
                (parent as? android.view.ViewGroup)?.removeView(this)
            }
            .start()
    }

    override fun onDetachedFromWindow() {
        stopBobbing()
        super.onDetachedFromWindow()
    }
}
