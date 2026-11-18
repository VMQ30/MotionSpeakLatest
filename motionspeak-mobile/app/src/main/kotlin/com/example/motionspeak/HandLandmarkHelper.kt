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

internal const val FEATURE_SIZE = 350
private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172
private const val MOTION_SCALE = 5.0f

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

    private val baseFeat = FloatArray(173)
    private val currentRawCoords = FloatArray(171)
    private val prevRawCoords = FloatArray(171)
    private var hasPrevRawCoords = false
    private var prevLeftActive = false
    private var prevRightActive = false
    private val handShapeFeat = FloatArray(126)
    private val motionFeat = FloatArray(51)
    private val finalFeatures = FloatArray(FEATURE_SIZE)

    // Temporal landmark smoothing (adaptive low-pass filtering) to eliminate micro-jitter
    private val smoothedLeft = FloatArray(42)
    private var hasSmoothedLeft = false
    private val smoothedRight = FloatArray(42)
    private var hasSmoothedRight = false

    // Pre-allocated flat buffer for pose coordinates to eliminate heap thrashing in live stream
    private val cachedPoseCoords = FloatArray(33 * 3)
    private val overlayFaceCoords = FloatArray(22)

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
            // PoseLandmarker runs on CPU (XNNPACK) to eliminate GPU pipeline contention with HandLandmarker
            val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.35f)
                .setMinPosePresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setResultListener { result, _ -> lastPoseResult = result }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
        } catch (e: Exception) {
            Log.e(TAG, "PoseLandmarker CPU init failed: ${e.message}", e)
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
        val hasPose = poseLms != null && poseLms.isNotEmpty()
        val poseCount = if (hasPose) minOf(poseLms!!.size, 33) else 0

        if (hasPose) {
            for (idx in 0 until poseCount) {
                val lm = poseLms!![idx]
                cachedPoseCoords[idx * 3]     = lm.x()
                cachedPoseCoords[idx * 3 + 1] = lm.y()
                cachedPoseCoords[idx * 3 + 2] = lm.z()
            }
        }

        // EMA shoulder reference for position- and scale-invariant normalization matching collect_data.py / train.py
        if (hasPose && poseCount > 12) {
            val lShX = cachedPoseCoords[11 * 3]
            val lShY = cachedPoseCoords[11 * 3 + 1]
            val lShZ = cachedPoseCoords[11 * 3 + 2]

            val rShX = cachedPoseCoords[12 * 3]
            val rShY = cachedPoseCoords[12 * 3 + 1]
            val rShZ = cachedPoseCoords[12 * 3 + 2]

            val cx = (lShX + rShX) * 0.5f
            val cy = (lShY + rShY) * 0.5f
            val cz = (lShZ + rShZ) * 0.5f
            val dx = lShX - rShX
            val dy = lShY - rShY
            val dz = lShZ - rShZ
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

        baseFeat.fill(0f)
        currentRawCoords.fill(0f)
        handShapeFeat.fill(0f)
        motionFeat.fill(0f)

        var leftOn = 0f
        var rightOn = 0f

        val hasPoseWrists = hasPose && poseCount > 16
        val poseLeftWristX = if (hasPoseWrists) cachedPoseCoords[15 * 3] else 0f
        val poseLeftWristY = if (hasPoseWrists) cachedPoseCoords[15 * 3 + 1] else 0f
        val poseRightWristX = if (hasPoseWrists) cachedPoseCoords[16 * 3] else 0f
        val poseRightWristY = if (hasPoseWrists) cachedPoseCoords[16 * 3 + 1] else 0f

        val assignedHands = ArrayList<Pair<DetectedHand, Boolean>>(2)

        if (validHands.size == 1) {
            val h = validHands[0]
            val wx = h.landmarks[0].x()
            val wy = h.landmarks[0].y()

            val isLeft = if (hasPoseWrists) {
                val dL = sqrt((wx - poseLeftWristX) * (wx - poseLeftWristX) + (wy - poseLeftWristY) * (wy - poseLeftWristY))
                val dR = sqrt((wx - poseRightWristX) * (wx - poseRightWristX) + (wy - poseRightWristY) * (wy - poseRightWristY))
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

            var h0IsLeft = if (hasPoseWrists) {
                val distA = sqrt((w0x - poseLeftWristX) * (w0x - poseLeftWristX) + (w0y - poseLeftWristY) * (w0y - poseLeftWristY)) +
                            sqrt((w1x - poseRightWristX) * (w1x - poseRightWristX) + (w1y - poseRightWristY) * (w1y - poseRightWristY))
                val distB = sqrt((w0x - poseRightWristX) * (w0x - poseRightWristX) + (w0y - poseRightWristY) * (w0y - poseRightWristY)) +
                            sqrt((w1x - poseLeftWristX) * (w1x - poseLeftWristX) + (w1y - poseLeftWristY) * (w1y - poseLeftWristY))
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
            if (isLeft) leftOn = 1.0f else rightOn = 1.0f

            for (j in 0 until 21) {
                val sx = smoothedPts[j * 2]
                val sy = smoothedPts[j * 2 + 1]
                val sz = hand.landmarks[j].z()
                val nx = normX(sx)
                val ny = normY(sy)
                val nz = normZ(sz)

                baseFeat[offset + j * 3]     = nx
                baseFeat[offset + j * 3 + 1] = ny
                baseFeat[offset + j * 3 + 2] = nz
                currentRawCoords[offset + j * 3]     = nx
                currentRawCoords[offset + j * 3 + 1] = ny
                currentRawCoords[offset + j * 3 + 2] = nz
            }
        }

        if (!leftDetected) hasSmoothedLeft = false
        if (!rightDetected) hasSmoothedRight = false

        // Pose reference landmarks matching collect_data.py
        if (hasPose) {
            for (k in POSE_IDX.indices) {
                val idx = POSE_IDX[k]
                if (idx < poseCount) {
                    val px = cachedPoseCoords[idx * 3]
                    val py = cachedPoseCoords[idx * 3 + 1]
                    val pz = cachedPoseCoords[idx * 3 + 2]
                    val nx = normX(px)
                    val ny = normY(py)
                    val nz = normZ(pz)
                    baseFeat[126 + k * 3]     = nx
                    baseFeat[126 + k * 3 + 1] = ny
                    baseFeat[126 + k * 3 + 2] = nz
                    currentRawCoords[126 + k * 3]     = nx
                    currentRawCoords[126 + k * 3 + 1] = ny
                    currentRawCoords[126 + k * 3 + 2] = nz
                }
            }
        }

        // Hand presence flags (171: left_on, 172: right_on)
        baseFeat[LEFT_FLAG] = leftOn
        baseFeat[RIGHT_FLAG] = rightOn

        // Zero out Z coordinates for base features (matching meta.json: no_z = true)
        for (i in 0 until 171 step 3) {
            baseFeat[i + 2] = 0f
        }

        // Hand shape features: 21 relative points normalized by hand span (middle MCP to wrist)
        if (leftOn > 0f) {
            val wx = baseFeat[0]; val wy = baseFeat[1]; val wz = baseFeat[2]
            val mx = baseFeat[9 * 3]; val my = baseFeat[9 * 3 + 1]; val mz = baseFeat[9 * 3 + 2]
            val dx = mx - wx; val dy = my - wy; val dz = mz - wz
            var size = sqrt(dx * dx + dy * dy + dz * dz)
            if (size < 1e-6f) size = 1e-6f
            for (j in 0 until 21) {
                handShapeFeat[j * 3]     = (baseFeat[j * 3] - wx) / size
                handShapeFeat[j * 3 + 1] = (baseFeat[j * 3 + 1] - wy) / size
                handShapeFeat[j * 3 + 2] = (baseFeat[j * 3 + 2] - wz) / size
            }
        }

        if (rightOn > 0f) {
            val wx = baseFeat[63]; val wy = baseFeat[64]; val wz = baseFeat[65]
            val mx = baseFeat[63 + 9 * 3]; val my = baseFeat[63 + 9 * 3 + 1]; val mz = baseFeat[63 + 9 * 3 + 2]
            val dx = mx - wx; val dy = my - wy; val dz = mz - wz
            var size = sqrt(dx * dx + dy * dy + dz * dz)
            if (size < 1e-6f) size = 1e-6f
            for (j in 0 until 21) {
                handShapeFeat[63 + j * 3]     = (baseFeat[63 + j * 3] - wx) / size
                handShapeFeat[63 + j * 3 + 1] = (baseFeat[63 + j * 3 + 1] - wy) / size
                handShapeFeat[63 + j * 3 + 2] = (baseFeat[63 + j * 3 + 2] - wz) / size
            }
        }

        // Motion features: deltas across frames scaled by MOTION_SCALE
        if (hasPrevRawCoords) {
            for (k in 0 until 15) {
                motionFeat[k * 3]     = (currentRawCoords[126 + k * 3] - prevRawCoords[126 + k * 3]) * MOTION_SCALE
                motionFeat[k * 3 + 1] = (currentRawCoords[126 + k * 3 + 1] - prevRawCoords[126 + k * 3 + 1]) * MOTION_SCALE
                motionFeat[k * 3 + 2] = (currentRawCoords[126 + k * 3 + 2] - prevRawCoords[126 + k * 3 + 2]) * MOTION_SCALE
            }
            if (leftOn > 0f && prevLeftActive) {
                motionFeat[45] = (currentRawCoords[0] - prevRawCoords[0]) * MOTION_SCALE
                motionFeat[46] = (currentRawCoords[1] - prevRawCoords[1]) * MOTION_SCALE
                motionFeat[47] = (currentRawCoords[2] - prevRawCoords[2]) * MOTION_SCALE
            }
            if (rightOn > 0f && prevRightActive) {
                motionFeat[48] = (currentRawCoords[63] - prevRawCoords[63]) * MOTION_SCALE
                motionFeat[49] = (currentRawCoords[64] - prevRawCoords[64]) * MOTION_SCALE
                motionFeat[50] = (currentRawCoords[65] - prevRawCoords[65]) * MOTION_SCALE
            }
        }
        System.arraycopy(currentRawCoords, 0, prevRawCoords, 0, 171)
        hasPrevRawCoords = true
        prevLeftActive = (leftOn > 0f)
        prevRightActive = (rightOn > 0f)

        System.arraycopy(baseFeat, 0, finalFeatures, 0, 173)
        System.arraycopy(handShapeFeat, 0, finalFeatures, 173, 126)
        System.arraycopy(motionFeat, 0, finalFeatures, 299, 51)

        val overlayFaceLandmarks = ArrayList<FloatArray>(1)
        if (hasPose && poseCount > 10) {
            for (j in 0..10) {
                overlayFaceCoords[j * 2]     = cachedPoseCoords[j * 3]
                overlayFaceCoords[j * 2 + 1] = cachedPoseCoords[j * 3 + 1]
            }
            overlayFaceLandmarks.add(overlayFaceCoords)
        }

        val totalHands = (if (leftOn > 0f) 1 else 0) + (if (rightOn > 0f) 1 else 0)
        val sinceSubmitMs = if (lastSubmitNs > 0L) (System.nanoTime() - lastSubmitNs) / 1e6 else 0.0
        val avgScore = if (validHands.isNotEmpty()) validHands.map { it.score }.average().toFloat() else 0.5f

        return LandmarkResult(
            handsDetected = totalHands,
            features = finalFeatures,
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
        baseFeat.fill(0f)
        currentRawCoords.fill(0f)
        prevRawCoords.fill(0f)
        handShapeFeat.fill(0f)
        motionFeat.fill(0f)
        finalFeatures.fill(0f)
        cachedPoseCoords.fill(0f)
        overlayFaceCoords.fill(0f)
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
