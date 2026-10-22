package com.example.motionspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/**
 * Subset of the 478 MediaPipe face mesh connections that form the key contours:
 * oval silhouette, lips, left eye, right eye, left eyebrow, right eyebrow.
 * Drawing ALL 478 connections makes the overlay too dense and distracting.
 */
private val FACE_SILHOUETTE = listOf(
    // Oval (jawline + forehead)
    10 to 338, 338 to 297, 297 to 332, 332 to 284, 284 to 251, 251 to 389,
    389 to 356, 356 to 454, 454 to 323, 323 to 361, 361 to 288, 288 to 397,
    397 to 365, 365 to 379, 379 to 378, 378 to 400, 400 to 377, 377 to 152,
    152 to 148, 148 to 176, 176 to 149, 149 to 150, 150 to 136, 136 to 172,
    172 to 58, 58 to 132, 132 to 93, 93 to 234, 234 to 127, 127 to 162,
    162 to 21, 21 to 54, 54 to 103, 103 to 67, 67 to 109, 109 to 10,
    // Lips outer
    61 to 146, 146 to 91, 91 to 181, 181 to 84, 84 to 17, 17 to 314,
    314 to 405, 405 to 321, 321 to 375, 375 to 291, 291 to 61,
    // Left eye
    33 to 7, 7 to 163, 163 to 144, 144 to 145, 145 to 153, 153 to 154,
    154 to 155, 155 to 133, 133 to 173, 173 to 157, 157 to 158, 158 to 159,
    159 to 160, 160 to 161, 161 to 246, 246 to 33,
    // Right eye
    263 to 249, 249 to 390, 390 to 373, 373 to 374, 374 to 380, 380 to 381,
    381 to 382, 382 to 362, 362 to 398, 398 to 384, 384 to 385, 385 to 386,
    386 to 387, 387 to 388, 388 to 466, 466 to 263,
    // Left eyebrow
    46 to 53, 53 to 52, 52 to 65, 65 to 55, 55 to 107, 107 to 66, 66 to 105,
    105 to 63, 63 to 70, 70 to 46,
    // Right eyebrow
    276 to 283, 283 to 282, 282 to 295, 295 to 285, 285 to 336, 336 to 296,
    296 to 334, 334 to 293, 293 to 300, 300 to 276
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
    private val faceDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Bright cyan
        style = Paint.Style.FILL
    }
    private val faceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80D8FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // Each hand is a flat FloatArray of interleaved x,y (42 floats). landmark i = (arr[2i], arr[2i+1]).
    private var landmarks: List<FloatArray> = emptyList()
    // Each face is a flat FloatArray of interleaved x,y (478*2 floats for full mesh).
    private var faceLandmarks: List<FloatArray> = emptyList()
    private var srcWidth = 1f
    private var srcHeight = 1f
    private var isMirrored = false

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

        // ── Draw face landmarks ──────────────────────────────────────────
        for (face in faceLandmarks) {
            val n = face.size / 2
            if (n > 20) {
                // Full face mesh
                for ((a, b) in FACE_SILHOUETTE) {
                    if (a < n && b < n) {
                        canvas.drawLine(
                            drawX(face[a * 2]),
                            drawY(face[a * 2 + 1]),
                            drawX(face[b * 2]),
                            drawY(face[b * 2 + 1]),
                            faceLinePaint
                        )
                    }
                }
                for ((a, _) in FACE_SILHOUETTE) {
                    if (a < n) {
                        canvas.drawCircle(
                            drawX(face[a * 2]),
                            drawY(face[a * 2 + 1]),
                            4f, faceDotPaint
                        )
                    }
                }
                val keyFacePoints = intArrayOf(1, 33, 133, 263, 362, 61, 291, 17, 10, 152)
                for (idx in keyFacePoints) {
                    if (idx < n) {
                        canvas.drawCircle(
                            drawX(face[idx * 2]),
                            drawY(face[idx * 2 + 1]),
                            6f, faceDotPaint
                        )
                    }
                }
            } else {
                // Key facial points (nose, eyes, ears, mouth)
                for (i in 0 until n) {
                    canvas.drawCircle(
                        drawX(face[i * 2]),
                        drawY(face[i * 2 + 1]),
                        6f, faceDotPaint
                    )
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
    }
}
