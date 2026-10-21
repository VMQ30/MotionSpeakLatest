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
private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172
private const val MOTION_SCALE = 5.0f

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

    private var prevRawCoords: FloatArray? = null

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
        val currentRawCoords = FloatArray(171)

        val numHands = minOf(result.landmarks().size, 2)
        for (i in 0 until numHands) {
            val lms = result.landmarks()[i]
            val isLeft = result.handednesses().getOrNull(i)?.getOrNull(0)?.categoryName() == "Left"
            val base = if (isLeft) 0 else 63

            if (isLeft) baseFeat[LEFT_FLAG] = 1.0f else baseFeat[RIGHT_FLAG] = 1.0f

            for (j in 0 until 21) {
                baseFeat[base + j * 3] = lms[j].x()
                baseFeat[base + j * 3 + 1] = lms[j].y()
                baseFeat[base + j * 3 + 2] = lms[j].z()

                currentRawCoords[base + j * 3] = lms[j].x()
                currentRawCoords[base + j * 3 + 1] = lms[j].y()
                currentRawCoords[base + j * 3 + 2] = lms[j].z()
            }
        }

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

        for (i in 0 until 171 step 3) baseFeat[i + 2] = 0f

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

        val motionFeat = FloatArray(51)
        if (prevRawCoords != null) {
            for (k in 0 until 15) {
                motionFeat[k * 3] = (currentRawCoords[126 + k * 3] - prevRawCoords!![126 + k * 3]) * MOTION_SCALE
                motionFeat[k * 3 + 1] = (currentRawCoords[126 + k * 3 + 1] - prevRawCoords!![126 + k * 3 + 1]) * MOTION_SCALE
                motionFeat[k * 3 + 2] = (currentRawCoords[126 + k * 3 + 2] - prevRawCoords!![126 + k * 3 + 2]) * MOTION_SCALE
            }
            motionFeat[45] = (currentRawCoords[0] - prevRawCoords!![0]) * MOTION_SCALE
            motionFeat[46] = (currentRawCoords[1] - prevRawCoords!![1]) * MOTION_SCALE
            motionFeat[47] = (currentRawCoords[2] - prevRawCoords!![2]) * MOTION_SCALE
            motionFeat[48] = (currentRawCoords[63] - prevRawCoords!![63]) * MOTION_SCALE
            motionFeat[49] = (currentRawCoords[64] - prevRawCoords!![64]) * MOTION_SCALE
            motionFeat[50] = (currentRawCoords[65] - prevRawCoords!![65]) * MOTION_SCALE
        }
        prevRawCoords = currentRawCoords

        val finalFeatures = FloatArray(350)
        System.arraycopy(baseFeat, 0, finalFeatures, 0, 173)
        System.arraycopy(handShapeFeat, 0, finalFeatures, 173, 126)
        System.arraycopy(motionFeat, 0, finalFeatures, 299, 51)

        return LandmarkResult(numHands, finalFeatures, sourceWidth = lastSourceWidth, sourceHeight = lastSourceHeight)
    }

    fun close() {
        landmarker?.close()
        poseLandmarker?.close()
    }

    object Cache {
        @Volatile private var instance: HandLandmarkHelper? = null
        private val buildLock = Any()

        fun acquire(context: Context, onResult: (LandmarkResult) -> Unit): HandLandmarkHelper {
            instance?.let { return install(it, onResult) }
            synchronized(buildLock) {
                instance?.let { return install(it, onResult) }
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
            if (sink == null || current.resultSink === sink) current.resultSink = null
        }
    }
}