package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

private const val TAG = "HandLandmarkHelper"

internal const val FEATURE_SIZE = 147

const val POSE_DETECT_INTERVAL = 2
val POSE_KEYPOINTS = intArrayOf(0, 11, 12, 13, 14, 15, 16)

private val POSE_IDX = intArrayOf(0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24)

data class LandmarkResult(
    val handsDetected: Int,
    val features: FloatArray,
    val landmarks: List<FloatArray> = emptyList(),
    val faceLandmarks: List<FloatArray> = emptyList(),
    val sourceWidth: Int = 1,
    val sourceHeight: Int = 1,
    val handSlots: List<Int> = emptyList(), // 0 = Left (green), 1 = Right (orange)
    val landmarkLatencyMs: Double = 0.0,
    val detectionConfidence: Float = 0.5f,
    val frameCaptureTimestampMs: Long = 0L
)

class HandLandmarkHelper(
    private val context: Context,
    private val onResult: ((LandmarkResult) -> Unit)? = null
) {
    @Volatile var resultSink: ((LandmarkResult) -> Unit)? = onResult
    private var landmarker: HandLandmarker? = null
    @Volatile private var poseLandmarker: PoseLandmarker? = null
    @Volatile private var lastPoseResult: PoseLandmarkerResult? = null

    @Volatile private var lastSubmitNs = 0L
    @Volatile private var lastSourceWidth = 1
    @Volatile private var lastSourceHeight = 1
    @Volatile private var lastFrameTimeMs = 0L
    @Volatile private var latestFrameCaptureMs = 0L
    private var frameCounter = 0L

    // EMA shoulder reference for position- and scale-invariant normalization matching collect_data.py / train.py
    private var smoothedCenterX = 0.5f
    private var smoothedCenterY = 0.45f
    private var smoothedCenterZ = 0.0f
    private var smoothedShoulderWidth = 0.25f
    private var hasShoulderRef = false

    private var hasPrevRawCoords = false
    private var prevLeftActive = false
    private var prevRightActive = false
    private val finalFeatures = FloatArray(FEATURE_SIZE)

    // Temporal landmark smoothing (adaptive low-pass filtering) to eliminate micro-jitter
    private val smoothedLeft = FloatArray(42)
    private var hasSmoothedLeft = false
    private val smoothedRight = FloatArray(42)
    private var hasSmoothedRight = false

    init {
        val allowGpu = AppSettings.getInstance(context).isInferenceGpuEnabled
        val primaryDelegate = if (allowGpu) Delegate.GPU else Delegate.CPU
        try {
            val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(primaryDelegate).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.50f)
                .setMinHandPresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.45f)
                .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult, latestFrameCaptureMs)) }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, handOptions)
        } catch (e: Exception) {
            Log.w(TAG, "HandLandmarker GPU init failed, trying CPU: ${e.message}")
            try {
                val handOptionsCpu = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.50f)
                    .setMinHandPresenceConfidence(0.50f)
                    .setMinTrackingConfidence(0.45f)
                    .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult, latestFrameCaptureMs)) }
                    .build()
                landmarker = HandLandmarker.createFromOptions(context, handOptionsCpu)
            } catch (e2: Exception) {
                Log.e(TAG, "HandLandmarker CPU init failed: ${e2.message}", e2)
            }
        }

        try {
            val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(primaryDelegate).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.35f)
                .setMinPosePresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setResultListener { result, _ -> lastPoseResult = result }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
        } catch (e: Exception) {
            Log.w(TAG, "PoseLandmarker GPU init failed, trying CPU: ${e.message}")
            try {
                val poseOptionsCpu = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.35f)
                    .setMinPosePresenceConfidence(0.35f)
                    .setMinTrackingConfidence(0.35f)
                    .setResultListener { result, _ -> lastPoseResult = result }
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptionsCpu)
            } catch (e2: Exception) {
                Log.e(TAG, "PoseLandmarker CPU init failed: ${e2.message}", e2)
            }
        }
    }

    fun detectAsync(bitmap: Bitmap, frameTimestampMs: Long, frameCaptureTimestampMs: Long = frameTimestampMs) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        lastSourceWidth = bitmap.width
        lastSourceHeight = bitmap.height
        lastSubmitNs = System.nanoTime()
        latestFrameCaptureMs = frameCaptureTimestampMs

        val ts = maxOf(frameTimestampMs, lastFrameTimeMs + 1)
        lastFrameTimeMs = ts

        try {
            landmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "hand detectAsync error: ${e.message}")
        }

        frameCounter++
        // Submit pose on alternate frames to reduce GPU contention and allow hand tracking maximum speed
        if (frameCounter % 2L == 0L) {
            try {
                poseLandmarker?.detectAsync(mpImage, ts)
            } catch (e: Exception) {
                Log.e(TAG, "pose detectAsync error: ${e.message}")
            }
        }
    }

    private data class DetectedHand(
        val landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        val isLeftReported: Boolean,
        val score: Float
    )

    private fun smoothLandmarks(
        lms: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        slot: Int
    ): FloatArray {
        val target = if (slot == 0) smoothedLeft else smoothedRight
        val hasSmoothed = if (slot == 0) hasSmoothedLeft else hasSmoothedRight
        val pts = FloatArray(42)

        for (j in 0 until 21) {
            val rx = lms[j].x()
            val ry = lms[j].y()
            if (!hasSmoothed) {
                target[j * 2] = rx
                target[j * 2 + 1] = ry
            } else {
                val dx = rx - target[j * 2]
                val dy = ry - target[j * 2 + 1]
                val dist = sqrt(dx * dx + dy * dy)
                val alpha = (0.55f + (dist / 0.035f) * 0.30f).coerceIn(0.55f, 0.85f)
                target[j * 2] += alpha * dx
                target[j * 2 + 1] += alpha * dy
            }
            pts[j * 2] = target[j * 2]
            pts[j * 2 + 1] = target[j * 2 + 1]
        }
        if (slot == 0) hasSmoothedLeft = true else hasSmoothedRight = true
        return pts
    }

    private fun isLandmarkQualityAcceptable(lms: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        if (lms.size < 21) return false
        val wrist = lms[0]
        val middleMcp = lms[9]

        if (wrist.x() < 0.015f || wrist.x() > 0.985f || wrist.y() < 0.015f || wrist.y() > 0.985f) {
            return false
        }

        var edgeCount = 0
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (i in 0 until 21) {
            val x = lms[i].x()
            val y = lms[i].y()
            if (x < 0.015f || x > 0.985f || y < 0.015f || y > 0.985f) {
                edgeCount++
            }
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (edgeCount > 3) return false

        val handWidth = maxX - minX
        val handHeight = maxY - minY
        if (handWidth < 0.02f || handHeight < 0.02f) return false

        val dx = middleMcp.x() - wrist.x()
        val dy = middleMcp.y() - wrist.y()
        val span = sqrt(dx * dx + dy * dy)
        if (span < 0.025f || span > 0.65f) return false

        return true
    }

    private fun parseResult(
        result: HandLandmarkerResult,
        pose: PoseLandmarkerResult?,
        frameCaptureTimestampMs: Long = 0L
    ): LandmarkResult {
        val poseLms = pose?.landmarks()?.firstOrNull()
        val poseCoords: List<FloatArray>? = poseLms?.map {
            floatArrayOf(it.x(), it.y(), it.z())
        }

        // EMA shoulder reference for position- and scale-invariant normalization matching collect_data.py / train.py
        if (poseCoords != null && poseCoords.size > 12) {
            val lSh = poseCoords[11]
            val rSh = poseCoords[12]
            val cx = (lSh[0] + rSh[0]) * 0.5f
            val cy = (lSh[1] + rSh[1]) * 0.5f
            val cz = (lSh[2] + rSh[2]) * 0.5f
            val dx = lSh[0] - rSh[0]
            val dy = lSh[1] - rSh[1]
            val dz = lSh[2] - rSh[2]
            val w = sqrt(dx * dx + dy * dy + dz * dz)

            if (w > 0.05f) {
                if (!hasShoulderRef) {
                    smoothedCenterX = cx
                    smoothedCenterY = cy
                    smoothedCenterZ = cz
                    smoothedShoulderWidth = w
                    hasShoulderRef = true
                } else {
                    smoothedCenterX += 0.2f * (cx - smoothedCenterX)
                    smoothedCenterY += 0.2f * (cy - smoothedCenterY)
                    smoothedCenterZ += 0.2f * (cz - smoothedCenterZ)
                    smoothedShoulderWidth += 0.2f * (w - smoothedShoulderWidth)
                }
            }
        }

        val centerX = if (hasShoulderRef) smoothedCenterX else 0.5f
        val centerY = if (hasShoulderRef) smoothedCenterY else 0.45f
        val centerZ = if (hasShoulderRef) smoothedCenterZ else 0.0f
        val effWidth = if (hasShoulderRef && smoothedShoulderWidth > 1e-4f) smoothedShoulderWidth else 0.25f

        fun normX(x: Float): Float = (x - centerX) / effWidth
        fun normY(y: Float): Float = (y - centerY) / effWidth
        fun normZ(z: Float): Float = (z - centerZ) / effWidth

        val rawHands = result.landmarks()
        val rawHandedness = result.handednesses()
        val validHands = ArrayList<DetectedHand>(2)

        for (i in 0 until minOf(rawHands.size, 2)) {
            val lms = rawHands[i]
            val cat = rawHandedness.getOrNull(i)?.firstOrNull()
            val score = cat?.score() ?: 0f
            // Filter out low-confidence hallucinations (< 0.45 confidence)
            if (score < 0.45f) continue
            if (!isLandmarkQualityAcceptable(lms)) continue
            val isLeft = cat?.categoryName() == "Left"
            validHands.add(DetectedHand(lms, isLeft, score))
        }

        val overlayLandmarks = ArrayList<FloatArray>(validHands.size)
        val overlaySlots = ArrayList<Int>(validHands.size)

        finalFeatures.fill(0f)

        val hasPoseWrists = poseCoords != null && poseCoords.size > 16
        val poseLeftWrist = if (hasPoseWrists) poseCoords!![15] else null
        val poseRightWrist = if (hasPoseWrists) poseCoords!![16] else null

        val assignedHands = ArrayList<Pair<DetectedHand, Boolean>>(2)

        if (validHands.size == 1) {
            val h = validHands[0]
            val wx = h.landmarks[0].x()
            val wy = h.landmarks[0].y()

            val isLeft = if (poseLeftWrist != null && poseRightWrist != null) {
                val dL = sqrt((wx - poseLeftWrist[0]) * (wx - poseLeftWrist[0]) + (wy - poseLeftWrist[1]) * (wy - poseLeftWrist[1]))
                val dR = sqrt((wx - poseRightWrist[0]) * (wx - poseRightWrist[0]) + (wy - poseRightWrist[1]) * (wy - poseRightWrist[1]))
                if (kotlin.math.abs(dL - dR) > 0.06f) {
                    dL < dR
                } else {
                    h.isLeftReported
                }
            } else {
                h.isLeftReported
            }
            assignedHands.add(Pair(h, isLeft))
        } else if (validHands.size >= 2) {
            val h0 = validHands[0]
            val h1 = validHands[1]
            val w0x = h0.landmarks[0].x(); val w0y = h0.landmarks[0].y()
            val w1x = h1.landmarks[0].x(); val w1y = h1.landmarks[0].y()

            var h0IsLeft = if (poseLeftWrist != null && poseRightWrist != null) {
                val distA = sqrt((w0x - poseLeftWrist[0]) * (w0x - poseLeftWrist[0]) + (w0y - poseLeftWrist[1]) * (w0y - poseLeftWrist[1])) +
                            sqrt((w1x - poseRightWrist[0]) * (w1x - poseRightWrist[0]) + (w1y - poseRightWrist[1]) * (w1y - poseRightWrist[1]))
                val distB = sqrt((w0x - poseRightWrist[0]) * (w0x - poseRightWrist[0]) + (w0y - poseRightWrist[1]) * (w0y - poseRightWrist[1])) +
                            sqrt((w1x - poseLeftWrist[0]) * (w1x - poseLeftWrist[0]) + (w1y - poseLeftWrist[1]) * (w1y - poseLeftWrist[1]))
                if (kotlin.math.abs(distA - distB) > 0.06f) {
                    distA < distB
                } else {
                    w0x < w1x
                }
            } else {
                if (h0.isLeftReported != h1.isLeftReported) {
                    h0.isLeftReported
                } else {
                    w0x < w1x
                }
            }

            assignedHands.add(Pair(h0, h0IsLeft))
            assignedHands.add(Pair(h1, !h0IsLeft))
        }

        var leftDetected = false
        var rightDetected = false

        for ((hand, isLeft) in assignedHands) {
            val slot = if (isLeft) 0 else 1
            if (isLeft) leftDetected = true else rightDetected = true

            // Adaptive temporal smoothing per hand slot
            val smoothedPts = smoothLandmarks(hand.landmarks, slot)
            overlayLandmarks.add(smoothedPts)
            overlaySlots.add(slot)

            val offset = if (isLeft) 0 else 63
            for (j in 0 until 21) {
                finalFeatures[offset + j * 3]     = hand.landmarks[j].x()
                finalFeatures[offset + j * 3 + 1] = hand.landmarks[j].y()
                finalFeatures[offset + j * 3 + 2] = hand.landmarks[j].z()
            }
        }

        if (!leftDetected) hasSmoothedLeft = false
        if (!rightDetected) hasSmoothedRight = false

        // Pose reference keypoints: 7 keypoints [0, 11, 12, 13, 14, 15, 16] -> 21 floats at offset 126..146
        if (poseCoords != null) {
            for (k in POSE_KEYPOINTS.indices) {
                val kp = POSE_KEYPOINTS[k]
                if (kp < poseCoords.size) {
                    val p = poseCoords[kp]
                    finalFeatures[POSE_BASE_147 + k * 3]     = p[0]
                    finalFeatures[POSE_BASE_147 + k * 3 + 1] = p[1]
                    finalFeatures[POSE_BASE_147 + k * 3 + 2] = p[2]
                }
            }
        }

        // Normalize the 147-float vector (wrist-centered hands, shoulder-centered pose)
        // exactly matching Python preprocessor.normalize_frame
        normalizeFrame(finalFeatures)

        val overlayFaceLandmarks = ArrayList<FloatArray>(1)
        if (poseCoords != null && poseCoords.size > 10) {
            val coords = FloatArray(22)
            for (j in 0..10) {
                coords[j * 2]     = poseCoords[j][0]
                coords[j * 2 + 1] = poseCoords[j][1]
            }
            overlayFaceLandmarks.add(coords)
        }

        val totalHands = (if (leftDetected) 1 else 0) + (if (rightDetected) 1 else 0)
        val sinceSubmitMs = if (lastSubmitNs > 0L) (System.nanoTime() - lastSubmitNs) / 1e6 else 0.0
        val avgScore = if (validHands.isNotEmpty()) validHands.map { it.score }.average().toFloat() else 0.5f

        return LandmarkResult(
            handsDetected = totalHands,
            features = finalFeatures.copyOf(),
            landmarks = overlayLandmarks,
            faceLandmarks = overlayFaceLandmarks,
            sourceWidth = lastSourceWidth,
            sourceHeight = lastSourceHeight,
            handSlots = overlaySlots,
            landmarkLatencyMs = sinceSubmitMs,
            detectionConfidence = avgScore,
            frameCaptureTimestampMs = frameCaptureTimestampMs
        )
    }

    fun reset() {
        hasShoulderRef = false
        hasPrevRawCoords = false
        prevLeftActive = false
        prevRightActive = false
        hasSmoothedLeft = false
        hasSmoothedRight = false
        smoothedLeft.fill(0f)
        smoothedRight.fill(0f)
        smoothedCenterX = 0.5f
        smoothedCenterY = 0.45f
        smoothedCenterZ = 0.0f
        smoothedShoulderWidth = 0.25f
        finalFeatures.fill(0f)
    }

    fun close() {
        landmarker?.close()
        poseLandmarker?.close()
        reset()
    }

    object Cache {
        @Volatile private var instance: HandLandmarkHelper? = null
        private val buildLock = Any()

        @Volatile private var prewarmInFlight = false

        fun prewarm(context: Context) {
            if (instance != null || prewarmInFlight) return
            val appCtx = context.applicationContext
            synchronized(buildLock) {
                if (instance != null || prewarmInFlight) return
                prewarmInFlight = true
            }
            Thread({
                try {
                    synchronized(buildLock) {
                        if (instance == null) {
                            instance = HandLandmarkHelper(appCtx)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "prewarm failed: ${e.message}", e)
                } finally {
                    prewarmInFlight = false
                }
            }, "motionspeak-prewarm").apply { isDaemon = true; start() }
        }

        fun acquire(context: Context, onResult: (LandmarkResult) -> Unit): HandLandmarkHelper {
            instance?.let {
                it.reset()
                return install(it, onResult)
            }
            synchronized(buildLock) {
                instance?.let {
                    it.reset()
                    return install(it, onResult)
                }
                val created = HandLandmarkHelper(context.applicationContext, onResult)
                instance = created
                return created
            }
        }

        @Synchronized
        private fun install(helper: HandLandmarkHelper, onResult: (LandmarkResult) -> Unit): HandLandmarkHelper {
            helper.resultSink = onResult
            return helper
        }

        @Synchronized
        fun release(sink: ((LandmarkResult) -> Unit)?) {
            val current = instance ?: return
            if (sink == null || current.resultSink === sink) {
                current.resultSink = null
                current.reset()
            }
        }
    }
}

internal const val FEATURE_SIZE_147 = 147
internal const val POSE_BASE_147    = 126
internal const val POSE_LSHOULDER_147 = 1
internal const val POSE_RSHOULDER_147 = 2
internal const val MAX_HAND_EXTENT = 5.0f

/**
 * Normalize a full 147-float frame in place: every PRESENT hand block, then the
 * pose block. Absent (all-zero) blocks are left untouched as the sentinel.
 *
 * MUST match motionspeak-ml preprocessor.normalize_frame exactly.
 */
internal fun normalizeFrame(features: FloatArray) {
    for (hand in 0..1) {
        val base = hand * 63
        var present = false
        for (k in base until base + 63) {
            if (features[k] != 0f) { present = true; break }
        }
        if (present) normalizeHandBlock(features, base)
    }
    normalizePoseBlock(features)
}

/**
 * True when every PRESENT hand in an already-normalized frame is a plausible size.
 * MUST stay identical to hand_extent_ok in motionspeak-ml preprocessor.py.
 */
internal fun handExtentOk(features: FloatArray): Boolean {
    for (hand in 0..1) {
        val base = hand * 63
        var present = false
        for (k in base until base + 63) {
            if (features[k] != 0f) { present = true; break }
        }
        if (!present) continue

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (j in 0..20) {
            val x = features[base + j * 3]
            val y = features[base + j * 3 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX - minX > MAX_HAND_EXTENT || maxY - minY > MAX_HAND_EXTENT) return false
    }
    return true
}

// Normalize one hand's 63-float block in place: wrist-center (landmark 0) + scale
// by 2D wrist->landmark-9 distance. Must match motionspeak-ml normalize_frame exactly.
internal fun normalizeHandBlock(features: FloatArray, base: Int) {
    val wx = features[base]
    val wy = features[base + 1]
    val wz = features[base + 2]
    val mx = features[base + 9 * 3]
    val my = features[base + 9 * 3 + 1]
    var d = kotlin.math.sqrt((mx - wx) * (mx - wx) + (my - wy) * (my - wy))
    if (d < 1e-6f) d = 1e-6f
    for (j in 0..20) {
        features[base + j * 3]     = (features[base + j * 3]     - wx) / d
        features[base + j * 3 + 1] = (features[base + j * 3 + 1] - wy) / d
        features[base + j * 3 + 2] = (features[base + j * 3 + 2] - wz) / d
    }
}

// Normalize the 21-float pose block in place: center on shoulder midpoint
// (x,y,z), scale by 2D L<->R shoulder distance, epsilon 1e-6. Must match
// motionspeak-ml preprocessor.normalize_frame pose block exactly.
internal fun normalizePoseBlock(features: FloatArray) {
    var present = false
    for (k in POSE_BASE_147 until POSE_BASE_147 + 21) {
        if (k < features.size && features[k] != 0f) { present = true; break }
    }
    if (!present) return
    val lsx = features[POSE_BASE_147 + POSE_LSHOULDER_147 * 3]
    val lsy = features[POSE_BASE_147 + POSE_LSHOULDER_147 * 3 + 1]
    val lsz = features[POSE_BASE_147 + POSE_LSHOULDER_147 * 3 + 2]
    val rsx = features[POSE_BASE_147 + POSE_RSHOULDER_147 * 3]
    val rsy = features[POSE_BASE_147 + POSE_RSHOULDER_147 * 3 + 1]
    val rsz = features[POSE_BASE_147 + POSE_RSHOULDER_147 * 3 + 2]
    val cx = (lsx + rsx) / 2f
    val cy = (lsy + rsy) / 2f
    val cz = (lsz + rsz) / 2f
    var sw = kotlin.math.sqrt((rsx - lsx) * (rsx - lsx) + (rsy - lsy) * (rsy - lsy))
    if (sw < 1e-6f) sw = 1e-6f
    for (k in 0..6) {
        features[POSE_BASE_147 + k * 3]     = (features[POSE_BASE_147 + k * 3]     - cx) / sw
        features[POSE_BASE_147 + k * 3 + 1] = (features[POSE_BASE_147 + k * 3 + 1] - cy) / sw
        features[POSE_BASE_147 + k * 3 + 2] = (features[POSE_BASE_147 + k * 3 + 2] - cz) / sw
    }
}

