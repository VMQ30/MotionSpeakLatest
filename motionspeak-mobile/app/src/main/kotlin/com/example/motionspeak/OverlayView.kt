package com.example.motionspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
        color = Color.rgb(180, 180, 180) // Light Gray: (180, 180, 180)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f) // Thin dashed style
    }
    private val emotionBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC111827") // Translucent dark slate
        style = Paint.Style.FILL
    }
    private val emotionBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0) // Prominent Green accent border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val emotionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Each hand is a flat FloatArray of interleaved x,y (42 floats). landmark i = (arr[2i], arr[2i+1]).
    private var landmarks: List<FloatArray> = emptyList()
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
    ) {
        landmarks = handLms
        faceLandmarks = faceLms
        srcWidth = w
        srcHeight = h
        isMirrored = mirrored
        invalidate()
    }

    /** Called to wipe landmarks when no tracking is active. */
    fun clear() {
        landmarks = emptyList()
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

        // ── Draw face bounding box (Gray, tight to face contour) ─────────
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

                    val left = minOf(x1, x2)
                    val right = maxOf(x1, x2)
                    val top = minOf(y1, y2)
                    val bottom = maxOf(y1, y2)

                    val faceRect = RectF(left, top, right, bottom)
                    canvas.drawRoundRect(faceRect, 8f, 8f, faceBoxPaint)
                }
            }
        }

        // ── Draw hand landmarks ──────────────────────────────────────────
        for ((handIdx, hand) in landmarks.withIndex()) {
            val dp = if (handIdx == 0) dotPaint else dot2Paint
            val lp = if (handIdx == 0) linePaint else line2Paint
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

        // ── Draw detected expression label in lower-middle portion ──────
        detectedEmotion?.takeIf { it.isNotEmpty() }?.let { emotionText ->
            val textBounds = android.graphics.Rect()
            emotionTextPaint.getTextBounds(emotionText, 0, emotionText.length, textBounds)
            val textW = textBounds.width().toFloat()
            val textH = textBounds.height().toFloat()

            val cx = width / 2f
            // Lower-middle portion of camera feed, directly above bottom sheet/controls
            val cy = height * 0.72f
            val padX = 32f
            val padY = 18f

            val badgeRect = android.graphics.RectF(
                cx - textW / 2f - padX,
                cy - textH / 2f - padY,
                cx + textW / 2f + padX,
                cy + textH / 2f + padY
            )
            val cornerRadius = badgeRect.height() / 2f
            canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, emotionBadgeBgPaint)
            canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, emotionBadgeBorderPaint)
            canvas.drawText(emotionText, cx, cy + textH / 2f - 4f, emotionTextPaint)
        }
    }
}
