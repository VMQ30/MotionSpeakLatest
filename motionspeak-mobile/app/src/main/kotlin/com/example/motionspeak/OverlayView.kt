package com.example.motionspeak

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

private val CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    0 to 9, 9 to 10, 10 to 11, 11 to 12,
    0 to 13, 13 to 14, 14 to 15, 15 to 16,
    0 to 17, 17 to 18, 18 to 19, 19 to 20,
    5 to 9, 9 to 13, 13 to 17
)

internal data class FillCenterTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/** Same uniform scale + centred crop used by PreviewView.ScaleType.FILL_CENTER. */
internal fun fillCenterTransform(
    sourceWidth: Float,
    sourceHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
): FillCenterTransform {
    if (sourceWidth <= 0f || sourceHeight <= 0f) {
        return FillCenterTransform(1f, 0f, 0f)
    }
    val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
    return FillCenterTransform(
        scale = scale,
        offsetX = (viewWidth - sourceWidth * scale) / 2f,
        offsetY = (viewHeight - sourceHeight * scale) / 2f,
    )
}

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /**
         * Maps detected emotional tones to distinct colors.
         */
        fun getEmotionColor(emotion: String?): Int {
            return when (emotion?.lowercase()) {
                "happy" -> Color.parseColor("#10B981")     // Vibrant Emerald / Green
                "sad" -> Color.parseColor("#3B82F6")       // Electric / Royal Blue
                "angry" -> Color.parseColor("#EF4444")     // Vibrant Crimson / Red
                "fear" -> Color.parseColor("#A855F7")      // Deep Purple / Violet
                "surprise" -> Color.parseColor("#F59E0B")  // Bright Amber / Orange
                "disgust" -> Color.parseColor("#84CC16")   // Lime Green
                "contempt" -> Color.parseColor("#EC4899")  // Hot Pink / Magenta
                "neutral" -> Color.parseColor("#06B6D4")   // Cyan
                else -> Color.parseColor("#06B6D4")        // Default Cyan accent
            }
        }
    }

    init {
        setWillNotDraw(false)
        elevation = 20f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Bright neon green
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#69F0AE")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dot2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800") // Vibrant orange for second hand
        style = Paint.Style.FILL
    }
    private val line2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB74D")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val faceBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val emotionBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val emotionBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val emotionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Scratch objects to avoid heap allocations during frame rendering
    private val scratchFaceRect = RectF()
    private val scratchBadgeRect = RectF()
    private val scratchTextBounds = Rect()

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Each hand is a flat FloatArray of interleaved x,y (42 floats). landmark i = (arr[2i], arr[2i+1]).
    private var landmarks: List<FloatArray> = emptyList()
    // Hand slots matching landmarks (0 = Left/green, 1 = Right/orange)
    private var handSlots: List<Int> = emptyList()
    // Each face is a flat FloatArray of interleaved x,y (478*2 floats for full mesh).
    private var faceLandmarks: List<FloatArray> = emptyList()
    private var detectedEmotion: String? = null
    private var srcWidth = 1f
    private var srcHeight = 1f
    private var isMirrored = false

    /** Update detected facial expression label displayed on camera. */
    fun setDetectedEmotion(emotion: String?) {
        if (detectedEmotion != emotion) {
            detectedEmotion = emotion
            invalidate()
        }
    }

    /** Called from MainActivity to update hand + face landmarks and redraw. */
    fun setLandmarks(
        handLms: List<FloatArray>,
        faceLms: List<FloatArray>,
        w: Float,
        h: Float,
        mirrored: Boolean = false,
        slots: List<Int> = emptyList(),
    ) {
        landmarks = handLms
        handSlots = slots
        faceLandmarks = faceLms
        srcWidth = w
        srcHeight = h
        isMirrored = mirrored
        invalidate()
    }

    /** Called to wipe landmarks when no tracking is active. */
    fun clear() {
        landmarks = emptyList()
        handSlots = emptyList()
        faceLandmarks = emptyList()
        detectedEmotion = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val transform = fillCenterTransform(
            srcWidth, srcHeight, width.toFloat(), height.toFloat()
        )

        fun drawX(normalizedX: Float): Float {
            val x = transform.offsetX + normalizedX * srcWidth * transform.scale
            return if (isMirrored) width - x else x
        }

        fun drawY(normalizedY: Float): Float =
            transform.offsetY + normalizedY * srcHeight * transform.scale

        val emotionColor = getEmotionColor(detectedEmotion)

        // ── Draw face bounding box (dynamic size based on face size, slightly transparent, emotion color) ─
        val faceBorderAlpha = 180 // ~70% opacity: slightly transparent
        val faceBorderColor = Color.argb(
            faceBorderAlpha,
            Color.red(emotionColor),
            Color.green(emotionColor),
            Color.blue(emotionColor)
        )
        faceBoxPaint.color = faceBorderColor

        for (face in faceLandmarks) {
            val n = face.size / 2
            if (n >= 4) {
                var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
                for (i in 0 until n) {
                    val x = face[i * 2]
                    val y = face[i * 2 + 1]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }

                if (maxX > minX && maxY > minY) {
                    val x1 = drawX(minX)
                    val x2 = drawX(maxX)
                    val y1 = drawY(minY)
                    val y2 = drawY(maxY)

                    val rawLeft = minOf(x1, x2)
                    val rawRight = maxOf(x1, x2)
                    val rawTop = minOf(y1, y2)
                    val rawBottom = maxOf(y1, y2)

                    val faceW = rawRight - rawLeft
                    val faceH = rawBottom - rawTop

                    // Dynamic padding proportional to user's face size
                    val padX = faceW * 0.10f
                    val padY = faceH * 0.12f

                    val left = (rawLeft - padX).coerceAtLeast(0f)
                    val right = (rawRight + padX).coerceAtMost(width.toFloat())
                    val top = (rawTop - padY).coerceAtLeast(0f)
                    val bottom = (rawBottom + padY).coerceAtMost(height.toFloat())

                    // Dynamic corner radius and stroke width based on face dimensions
                    val dynamicCornerRadius = (minOf(faceW, faceH) * 0.12f).coerceIn(12f, 36f)
                    val dynamicStrokeWidth = (minOf(faceW, faceH) * 0.016f).coerceIn(3f, 6f)
                    faceBoxPaint.strokeWidth = dynamicStrokeWidth

                    scratchFaceRect.set(left, top, right, bottom)
                    canvas.drawRoundRect(scratchFaceRect, dynamicCornerRadius, dynamicCornerRadius, faceBoxPaint)
                }
            }
        }

        // ── Draw hand landmarks ──────────────────────────────────────────
        for ((handIdx, hand) in landmarks.withIndex()) {
            val slot = handSlots.getOrElse(handIdx) { handIdx }
            val dp = if (slot == 0) dotPaint else dot2Paint
            val lp = if (slot == 0) linePaint else line2Paint
            val n = hand.size / 2  // number of landmarks (interleaved x,y)

            for ((a, b) in CONNECTIONS) {
                if (a < n && b < n) {
                    canvas.drawLine(
                        drawX(hand[a * 2]),
                        drawY(hand[a * 2 + 1]),
                        drawX(hand[b * 2]),
                        drawY(hand[b * 2 + 1]),
                        lp
                    )
                }
            }
            for (i in 0 until n) {
                canvas.drawCircle(
                    drawX(hand[i * 2]),
                    drawY(hand[i * 2 + 1]),
                    8f, dp
                )
            }
        }

        // ── Draw detected expression label at the top ───────────────────
        detectedEmotion?.takeIf { it.isNotEmpty() }?.let { emotionText ->
            val isDark = isNightMode
            val density = resources.displayMetrics.density

            // Background & text follow light/dark mode
            emotionBadgeBgPaint.color = if (isDark) {
                Color.parseColor("#D9111827") // Translucent dark slate
            } else {
                Color.parseColor("#E6FFFFFF") // Translucent clean white
            }

            emotionTextPaint.color = if (isDark) {
                Color.WHITE
            } else {
                Color.parseColor("#111827") // Dark slate text for high contrast in light mode
            }

            // Border color follows detected emotion
            emotionBadgeBorderPaint.color = emotionColor
            emotionBadgeBorderPaint.strokeWidth = 2.5f * density

            emotionTextPaint.getTextBounds(emotionText, 0, emotionText.length, scratchTextBounds)
            val textW = scratchTextBounds.width().toFloat()
            val textH = scratchTextBounds.height().toFloat()

            val cx = width / 2f
            // Positioned at the top of the screen feed
            val cy = maxOf(height * 0.08f, 64f * density)
            val padX = 22f * density
            val padY = 8f * density

            scratchBadgeRect.set(
                cx - textW / 2f - padX,
                cy - textH / 2f - padY,
                cx + textW / 2f + padX,
                cy + textH / 2f + padY
            )
            val cornerRadius = scratchBadgeRect.height() / 2f
            canvas.drawRoundRect(scratchBadgeRect, cornerRadius, cornerRadius, emotionBadgeBgPaint)
            canvas.drawRoundRect(scratchBadgeRect, cornerRadius, cornerRadius, emotionBadgeBorderPaint)
            canvas.drawText(emotionText, cx, cy + textH / 2f - 4f, emotionTextPaint)
        }
    }
}
