package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
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
    val faceLandmarks: List<FloatArray> = emptyList(),
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
    @Volatile private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var lastFaceResult: FaceLandmarkerResult? = null

    @Volatile private var lastSubmitNs = 0L
    @Volatile private var lastSourceWidth = 1
    @Volatile private var lastSourceHeight = 1
    @Volatile private var lastFrameTimeMs = 0L

    private var prevRawCoords: FloatArray? = null

    init {
        try {
            val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.GPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult, lastFaceResult)) }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, handOptions)
        } catch (e: Exception) {
            Log.e(TAG, "HandLandmarker init failed: ${e.message}", e)
        }

        try {
            val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.GPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setResultListener { result, _ -> lastPoseResult = result }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
        } catch (e: Exception) {
            Log.e(TAG, "PoseLandmarker init failed: ${e.message}", e)
        }

        try {
            val faceOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setResultListener { result, _ -> lastFaceResult = result }
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, faceOptions)
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmarker init failed: ${e.message}", e)
        }
    }

    fun detectAsync(bitmap: Bitmap, frameTimestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        lastSourceWidth = bitmap.width
        lastSourceHeight = bitmap.height
        lastSubmitNs = System.nanoTime()

        val ts = maxOf(frameTimestampMs, lastFrameTimeMs + 1)
        lastFrameTimeMs = ts

        try {
            landmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "hand detectAsync error: ${e.message}")
        }

        try {
            poseLandmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "pose detectAsync error: ${e.message}")
        }

        try {
            faceLandmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "face detectAsync error: ${e.message}")
        }
    }

    private fun parseResult(
        result: HandLandmarkerResult,
        pose: PoseLandmarkerResult?,
        face: FaceLandmarkerResult?
    ): LandmarkResult {
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

        // Build normalized (x,y) hand landmark arrays for the overlay renderer.
        // Each hand is a FloatArray of 42 floats: interleaved x,y for 21 landmarks.
        val overlayLandmarks = ArrayList<FloatArray>(numHands)
        for (i in 0 until numHands) {
            val lms = result.landmarks()[i]
            val coords = FloatArray(42)
            for (j in 0 until 21) {
                coords[j * 2]     = lms[j].x()
                coords[j * 2 + 1] = lms[j].y()
            }
            overlayLandmarks.add(coords)
        }

        // Build normalized (x,y) face landmark array for the overlay renderer.
        val overlayFaceLandmarks = ArrayList<FloatArray>(1)
        val faceLms = face?.faceLandmarks()?.firstOrNull()
        if (faceLms != null) {
            val coords = FloatArray(faceLms.size * 2)
            for (j in faceLms.indices) {
                coords[j * 2]     = faceLms[j].x()
                coords[j * 2 + 1] = faceLms[j].y()
            }
            overlayFaceLandmarks.add(coords)
        } else {
            // Fallback: extract face keypoints from Pose (nose, eyes, ears, mouth)
            val pLms = pose?.landmarks()?.firstOrNull()
            if (pLms != null && pLms.size > 10) {
                val coords = FloatArray(22)
                for (j in 0..10) {
                    coords[j * 2]     = pLms[j].x()
                    coords[j * 2 + 1] = pLms[j].y()
                }
                overlayFaceLandmarks.add(coords)
            }
        }

        return LandmarkResult(
            handsDetected = numHands,
            features = finalFeatures,
            landmarks = overlayLandmarks,
            faceLandmarks = overlayFaceLandmarks,
            sourceWidth = lastSourceWidth,
            sourceHeight = lastSourceHeight
        )
    }

    fun close() {
        landmarker?.close()
        poseLandmarker?.close()
        faceLandmarker?.close()
    }

    object Cache {
        @Volatile private var instance: HandLandmarkHelper? = null
        private val buildLock = Any()

        /**
         * Guards against two concurrent prewarm threads (e.g. first-launch Home fires
         * the timer, user completes onboarding, a new Home instance fires it again while
         * the first thread is still inside the slow MediaPipe GPU init).
         */
        @Volatile private var prewarmInFlight = false

        /**
         * Eagerly builds the helper on a dedicated background thread so the first
         * [acquire] returns an already-warm instance. Must NOT be called from the
         * main thread directly — Home wraps it in Handler.postDelayed but that
         * still runs on the main looper, so we always offload to a new thread.
         *
         * Safe to call many times: exits immediately when an instance already exists
         * or a prewarm thread is already running.
         */
        fun prewarm(context: Context) {
            // Double-checked read outside the lock for the fast path.
            if (instance != null || prewarmInFlight) return
            val appCtx = context.applicationContext
            synchronized(buildLock) {
                if (instance != null || prewarmInFlight) return
                prewarmInFlight = true
            }
            Thread({
                try {
                    synchronized(buildLock) {
                        // acquire() may have beaten us here — don't double-init.
                        if (instance == null) {
                            instance = HandLandmarkHelper(appCtx)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "prewarm failed: ${e.message}", e)
                } finally {
                    prewarmInFlight = false
                }
            }, "sigla-prewarm").apply { isDaemon = true; start() }
        }

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
