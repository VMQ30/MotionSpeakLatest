package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
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
internal const val POSE_BASE    = 126
internal const val POSE_LSHOULDER = 1
internal const val POSE_RSHOULDER = 2
internal val POSE_KEYPOINTS = intArrayOf(0, 11, 12, 13, 14, 15, 16)
internal const val MAX_HAND_EXTENT = 5.0f

private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172
private const val MOTION_SCALE = 5.0f

// Exact indices from your meta.json
private val POSE_IDX = intArrayOf(0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24)

data class LandmarkResult(
    val handsDetected: Int,
    val features: FloatArray,
    val landmarks: List<FloatArray> = emptyList(),
    val sourceWidth: Int = 1,
    val sourceHeight: Int = 1
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

    private var prevRawCoords: FloatArray? = null // Store previous raw coords for motion

    init {
        val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.GPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult)) }
            .build()
        landmarker = HandLandmarker.createFromOptions(context, handOptions)

        val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.GPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, _ -> lastPoseResult = result }
            .build()
        poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
    }

    fun detectAsync(bitmap: Bitmap, frameTimestampMs: Long) {
        val lmk = landmarker ?: return
        val mpImage = BitmapImageBuilder(bitmap).build()
        lastSourceWidth = bitmap.width
        lastSourceHeight = bitmap.height
        lastSubmitNs = System.nanoTime()
        lmk.detectAsync(mpImage, frameTimestampMs)
        poseLandmarker?.detectAsync(mpImage, frameTimestampMs)
    }

    private fun parseResult(result: HandLandmarkerResult, pose: PoseLandmarkerResult?): LandmarkResult {
        val baseFeat = FloatArray(173)
        val currentRawCoords = FloatArray(171) // For motion calculations

        // 1. Hands Base (126 features)
        val numHands = minOf(result.landmarks().size, 2)
        val drawData = ArrayList<FloatArray>(numHands)
        for (i in 0 until numHands) {
            val lms = result.landmarks()[i]
            val isLeft = result.handednesses().getOrNull(i)?.getOrNull(0)?.categoryName() == "Left"
            val base = if (isLeft) 0 else 63

            if (isLeft) baseFeat[LEFT_FLAG] = 1.0f else baseFeat[RIGHT_FLAG] = 1.0f

            val pts = FloatArray(42)
            for (j in 0 until 21) {
                baseFeat[base + j * 3] = lms[j].x()
                baseFeat[base + j * 3 + 1] = lms[j].y()
                baseFeat[base + j * 3 + 2] = lms[j].z()

                currentRawCoords[base + j * 3] = lms[j].x()
                currentRawCoords[base + j * 3 + 1] = lms[j].y()
                currentRawCoords[base + j * 3 + 2] = lms[j].z()

                pts[j * 2] = lms[j].x()
                pts[j * 2 + 1] = lms[j].y()
            }
            drawData.add(pts)
        }

        // 2. Pose Base (45 features)
        val poseLms = pose?.landmarks()?.firstOrNull()
        if (poseLms != null) {
            for (k in POSE_IDX.indices) {
                val p = poseLms[POSE_IDX[k]]
                baseFeat[126 + k * 3] = p.x()
                baseFeat[126 + k * 3 + 1] = p.y()
                baseFeat[126 + k * 3 + 2] = p.z()

                currentRawCoords[126 + k * 3] = p.x()
                currentRawCoords[126 + k * 3 + 1] = p.y()
                currentRawCoords[126 + k * 3 + 2] = p.z()
            }
        }

        // -- NO-Z STEP -- (Python zeros out Z in raw features)
        for (i in 0 until 171 step 3) {
            baseFeat[i + 2] = 0f
        }

        // 3. Hand Shape (126 features)
        val handShapeFeat = FloatArray(126)
        for (i in 0 until numHands) {
            val lms = result.landmarks()[i]
            val isLeft = result.handednesses().getOrNull(i)?.getOrNull(0)?.categoryName() == "Left"
            val base = if (isLeft) 0 else 63

            val wx = lms[0].x(); val wy = lms[0].y(); val wz = lms[0].z()
            val mx = lms[9].x(); val my = lms[9].y()
            var size = sqrt((mx - wx) * (mx - wx) + (my - wy) * (my - wy))
            if (size < 1e-6f) size = 1e-6f

            for (j in 0 until 21) {
                handShapeFeat[base + j * 3] = (lms[j].x() - wx) / size
                handShapeFeat[base + j * 3 + 1] = (lms[j].y() - wy) / size
                handShapeFeat[base + j * 3 + 2] = (lms[j].z() - wz) / size
            }
        }

        // 4. Motion (51 features)
        val motionFeat = FloatArray(51)
        if (prevRawCoords != null) {
            // Pose deltas
            for (k in 0 until 15) {
                motionFeat[k * 3] = (currentRawCoords[126 + k * 3] - prevRawCoords!![126 + k * 3]) * MOTION_SCALE
                motionFeat[k * 3 + 1] = (currentRawCoords[126 + k * 3 + 1] - prevRawCoords!![126 + k * 3 + 1]) * MOTION_SCALE
                motionFeat[k * 3 + 2] = (currentRawCoords[126 + k * 3 + 2] - prevRawCoords!![126 + k * 3 + 2]) * MOTION_SCALE
            }
            // Wrist deltas
            motionFeat[45] = (currentRawCoords[0] - prevRawCoords!![0]) * MOTION_SCALE
            motionFeat[46] = (currentRawCoords[1] - prevRawCoords!![1]) * MOTION_SCALE
            motionFeat[47] = (currentRawCoords[2] - prevRawCoords!![2]) * MOTION_SCALE

            motionFeat[48] = (currentRawCoords[63] - prevRawCoords!![63]) * MOTION_SCALE
            motionFeat[49] = (currentRawCoords[64] - prevRawCoords!![64]) * MOTION_SCALE
            motionFeat[50] = (currentRawCoords[65] - prevRawCoords!![65]) * MOTION_SCALE
        }
        prevRawCoords = currentRawCoords

        // Combine
        val finalFeatures = FloatArray(350)
        System.arraycopy(baseFeat, 0, finalFeatures, 0, 173)
        System.arraycopy(handShapeFeat, 0, finalFeatures, 173, 126)
        System.arraycopy(motionFeat, 0, finalFeatures, 299, 51)

        return LandmarkResult(numHands, finalFeatures, landmarks = drawData, sourceWidth = lastSourceWidth, sourceHeight = lastSourceHeight)
    }

    fun close() {
        landmarker?.close()
        poseLandmarker?.close()
    }

    object Cache {
        @Volatile
        private var instance: HandLandmarkHelper? = null
        private val buildLock = Any()
        private val prewarmStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun prewarm(context: Context) {
            if (instance != null || !prewarmStarted.compareAndSet(false, true)) return
            val app = context.applicationContext
            Thread({
                try {
                    synchronized(buildLock) {
                        if (instance == null) {
                            val created = HandLandmarkHelper(app) {}
                            created.resultSink = null
                            instance = created
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cache.prewarm failed: ${t.message}")
                }
            }, "hand-landmark-prewarm").apply {
                isDaemon = true
                start()
            }
        }

        fun acquire(
            context: Context,
            onResult: (LandmarkResult) -> Unit,
        ): HandLandmarkHelper {
            instance?.let { return install(it, onResult) }
            synchronized(buildLock) {
                instance?.let { return install(it, onResult) }
                val created = HandLandmarkHelper(context.applicationContext, onResult)
                instance = created
                return created
            }
        }

        @Synchronized
        private fun install(
            helper: HandLandmarkHelper,
            onResult: (LandmarkResult) -> Unit,
        ): HandLandmarkHelper {
            helper.resultSink = onResult
            return helper
        }

        @Synchronized
        fun release(sink: ((LandmarkResult) -> Unit)?) {
            val current = instance ?: return
            if (sink == null || current.resultSink === sink) {
                current.resultSink = null
            }
        }
    }
}

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

internal fun normalizeHandBlock(features: FloatArray, base: Int) {
    val wx = features[base]
    val wy = features[base + 1]
    val wz = features[base + 2]
    val mx = features[base + 9 * 3]
    val my = features[base + 9 * 3 + 1]
    var d = sqrt((mx - wx) * (mx - wx) + (my - wy) * (my - wy))
    if (d < 1e-6f) d = 1e-6f
    for (j in 0..20) {
        features[base + j * 3]     = (features[base + j * 3]     - wx) / d
        features[base + j * 3 + 1] = (features[base + j * 3 + 1] - wy) / d
        features[base + j * 3 + 2] = (features[base + j * 3 + 2] - wz) / d
    }
}

internal fun normalizePoseBlock(features: FloatArray) {
    var present = false
    val end = minOf(features.size, POSE_BASE + 7 * 3)
    for (k in POSE_BASE until end) {
        if (features[k] != 0f) { present = true; break }
    }
    if (!present) return
    val lsx = features[POSE_BASE + POSE_LSHOULDER * 3]
    val lsy = features[POSE_BASE + POSE_LSHOULDER * 3 + 1]
    val lsz = features[POSE_BASE + POSE_LSHOULDER * 3 + 2]
    val rsx = features[POSE_BASE + POSE_RSHOULDER * 3]
    val rsy = features[POSE_BASE + POSE_RSHOULDER * 3 + 1]
    val rsz = features[POSE_BASE + POSE_RSHOULDER * 3 + 2]
    val cx = (lsx + rsx) / 2f
    val cy = (lsy + rsy) / 2f
    val cz = (lsz + rsz) / 2f
    var sw = sqrt((rsx - lsx) * (rsx - lsx) + (rsy - lsy) * (rsy - lsy))
    if (sw < 1e-6f) sw = 1e-6f
    for (k in 0..6) {
        features[POSE_BASE + k * 3]     = (features[POSE_BASE + k * 3]     - cx) / sw
        features[POSE_BASE + k * 3 + 1] = (features[POSE_BASE + k * 3 + 1] - cy) / sw
        features[POSE_BASE + k * 3 + 2] = (features[POSE_BASE + k * 3 + 2] - cz) / sw
    }
}
