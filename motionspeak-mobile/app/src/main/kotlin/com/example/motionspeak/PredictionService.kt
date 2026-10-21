package com.example.motionspeak

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "PredictionService"
private const val SEQUENCE_LENGTH = 30
private const val NUM_CLASSES = 60
private const val MAX_GAP = 6
private const val PAD = 3
private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172

private const val POSE_LWRIST = 5   // local pose-block index (mediapipe landmark 15)
private const val POSE_RWRIST = 6   // local pose-block index (mediapipe landmark 16)
private val POSE_WRIST_XY_OLD = intArrayOf(
    POSE_BASE + POSE_LWRIST * 3, POSE_BASE + POSE_LWRIST * 3 + 1,
    POSE_BASE + POSE_RWRIST * 3, POSE_BASE + POSE_RWRIST * 3 + 1,
)
private val POSE_WRIST_XY_NEW = intArrayOf(
    POSE_BASE + 11 * 3, POSE_BASE + 11 * 3 + 1,
    POSE_BASE + 12 * 3, POSE_BASE + 12 * 3 + 1,
)

private val FALLBACK_LANDMARKS = intArrayOf(4, 8, 12, 16, 20)
private val FALLBACK_XY: IntArray = (0..1).flatMap { hand ->
    FALLBACK_LANDMARKS.flatMap { i -> listOf(hand * 63 + i * 3, hand * 63 + i * 3 + 1) }
}.toIntArray()

private const val VELOCITY_SMOOTH_WINDOW = 5
private const val VELOCITY_EDGE_MARGIN   = 0.20
private const val VELOCITY_TIE_EPS = 1e-5f

private const val MOTION_THRESHOLD       = 0.80f
private const val MOTION_EARLY_CONF      = 0.80f
private const val MOTION_MIN_MARGIN      = 0.15f
private const val EARLY_EXIT_THRESHOLD   = 0.90f
private const val EARLY_EXIT_STREAK      = 2
private const val MOTION_EARLY_STREAK    = 5
private const val MIN_POSE_FRAMES        = 24
private const val MIN_COMPLETE_GESTURE_FRAMES = 36
private const val MIN_REAL_FRAMES_FOR_FIRE = 20
private const val MIN_SEQUENCE_MOTION    = 0.50f
private const val BUFFER_CAPACITY        = 90

data class PredictionResult(
    val label: String,
    val confidence: Float,
    val isMotion: Boolean = true,
    val earlyExit: Boolean = false,
    val replacesPrevious: Boolean = false,
)

internal fun letterResult(c: LetterCommit): PredictionResult =
    PredictionResult(c.label, c.confidence, isMotion = c.isMotion, replacesPrevious = c.replacesPrevious)

internal fun nextLetterFrameCount(previous: Int, handPresent: Boolean, committed: Boolean): Int =
    if (!handPresent || committed) 0 else minOf(previous + 1, BUFFER_CAPACITY)

internal fun posePresent(frame: FloatArray): Boolean {
    val end = minOf(frame.size, if (frame.size >= 171) 171 else POSE_BASE + 21)
    for (k in POSE_BASE until end) if (frame[k] != 0f) return true
    return false
}

internal fun frameVelocity(prev: FloatArray, cur: FloatArray): Float {
    val idxs = if (posePresent(prev) && posePresent(cur)) {
        if (cur.size >= 171) POSE_WRIST_XY_NEW else POSE_WRIST_XY_OLD
    } else FALLBACK_XY
    var total = 0f
    for (j in idxs) {
        val d = cur[j] - prev[j]
        total += d * d
    }
    return sqrt(total)
}

internal fun peakVelocityIndex(frames: List<FloatArray>): Int {
    val n = frames.size
    if (n < 2) return 0

    val vel = FloatArray(n - 1) { i -> frameVelocity(frames[i], frames[i + 1]) }

    val smoothed: FloatArray
    if (vel.size >= VELOCITY_SMOOTH_WINDOW) {
        smoothed = FloatArray(vel.size)
        val half = VELOCITY_SMOOTH_WINDOW / 2
        for (i in vel.indices) {
            var sum = 0f
            for (k in -half..half) {
                val j = i + k
                if (j in vel.indices) sum += vel[j]
            }
            smoothed[i] = sum / VELOCITY_SMOOTH_WINDOW
        }
    } else {
        smoothed = vel
    }

    var lo = (vel.size * VELOCITY_EDGE_MARGIN).toInt()
    var hi = vel.size - lo
    if (hi <= lo) {
        lo = 0
        hi = vel.size
    }

    val centre = vel.size / 2.0
    var best = lo
    for (i in lo until hi) {
        if (smoothed[i] > smoothed[best] + VELOCITY_TIE_EPS) {
            best = i
        } else if (abs(smoothed[i] - smoothed[best]) <= VELOCITY_TIE_EPS) {
            if (abs(i - centre) < abs(best - centre)) best = i
        }
    }
    return best + 1
}

internal fun hasEnoughGestureEvidence(frameCount: Int, endOfGesture: Boolean): Boolean =
    endOfGesture || frameCount >= MIN_COMPLETE_GESTURE_FRAMES

internal fun hasSufficientPoseCoverage(frames: List<FloatArray>): Boolean =
    frames.count(::posePresent) >= MIN_POSE_FRAMES

internal fun sequenceMotionEnergy(frames: List<FloatArray>): Float {
    if (frames.size < 2) return 0f
    var total = 0f
    for (i in 0 until frames.size - 1) total += frameVelocity(frames[i], frames[i + 1])
    return total
}

internal fun hasSufficientMotion(frames: List<FloatArray>): Boolean =
    sequenceMotionEnergy(frames) >= MIN_SEQUENCE_MOTION

internal fun hasEnoughRealFrames(frameCount: Int): Boolean =
    frameCount >= MIN_REAL_FRAMES_FOR_FIRE

internal fun topPredictionMargin(probabilities: FloatArray, best: Int): Float {
    if (best !in probabilities.indices) return 0f
    var second = Float.NEGATIVE_INFINITY
    for (i in probabilities.indices) {
        if (i != best && probabilities[i] > second) second = probabilities[i]
    }
    return if (second == Float.NEGATIVE_INFINITY) 1f else probabilities[best] - second
}

internal fun acceptSingleWindow(probabilities: FloatArray): Int {
    if (probabilities.isEmpty()) return -1
    var best = 0
    for (i in 1 until probabilities.size) if (probabilities[i] > probabilities[best]) best = i
    if (probabilities[best] < MOTION_THRESHOLD) return -1
    if (topPredictionMargin(probabilities, best) < MOTION_MIN_MARGIN) return -1
    return best
}

internal fun meanPredictionProbabilities(
    history: Iterable<FloatArray>,
    classCount: Int,
): FloatArray {
    if (classCount <= 0) return FloatArray(0)
    val mean = FloatArray(classCount)
    var count = 0
    for (probabilities in history) {
        if (probabilities.size != classCount) continue
        for (i in 0 until classCount) mean[i] += probabilities[i]
        count++
    }
    if (count > 0) for (i in mean.indices) mean[i] /= count.toFloat()
    return mean
}

class PredictionService(private val context: Context) {
    enum class Vocabulary { WORDS, LETTERS }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val frameBuffer = ArrayDeque<FloatArray>()
    @Volatile var isReady = false

    var onResult: ((String) -> Unit)? = null
    var onCollecting: ((Float) -> Unit)? = null

    fun init() {
        try {
            interpreter = Interpreter(loadModel("motionspeak.tflite"))
            labels = loadLabels("labels.txt")
            isReady = true
            Log.i(TAG, "Model & Labels loaded. Classes: ${labels.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
        }
    }

    fun processFrame(features: FloatArray) {
        if (!isReady) return
        frameBuffer.addLast(features)

        // Keep buffer manageable (prevent memory leak)
        if (frameBuffer.size > 100) frameBuffer.removeFirst()

        // Wait until we have at least some active frames
        if (frameBuffer.size >= 15) {
            onCollecting?.invoke(frameBuffer.size / 30f)
            runInference()
        }
    }

    private fun runInference() {
        // 1. Gap Fill (Simple zero-fill for Kotlin, assuming MediaPipe catches most)
        // 2. Trim Active
        var firstActive = -1
        var lastActive = -1
        for (i in frameBuffer.indices) {
            if (frameBuffer[i][LEFT_FLAG] > 0f || frameBuffer[i][RIGHT_FLAG] > 0f) {
                if (firstActive == -1) firstActive = i
                lastActive = i
            }
        }

        if (firstActive == -1 || lastActive - firstActive < 6) return // Not enough active frames

        val start = maxOf(0, firstActive - PAD)
        val end = minOf(frameBuffer.size - 1, lastActive + PAD)
        val trimmed = frameBuffer.subList(start, end + 1).toList()

        // 3. Resample to 30 frames
        val resampled = ArrayList<FloatArray>(30)
        for (i in 0 until 30) {
            val idx = ((i.toFloat() / 29f) * (trimmed.size - 1)).roundToInt()
            resampled.add(trimmed[idx])
        }

        // 4. Run Model
        val input = Array(1) { Array(30) { FloatArray(FEATURE_SIZE) } }
        for (i in 0 until 30) input[0][i] = resampled[i]

        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter?.run(input, output)

        // 5. Evaluate
        var maxIdx = 0
        var maxConf = 0f
        for (i in 0 until NUM_CLASSES) {
            if (output[0][i] > maxConf) {
                maxConf = output[0][i]
                maxIdx = i
            }
        }

        if (maxConf > 0.80f) {
            val label = labels.getOrElse(maxIdx) { "Unknown" }
            onResult?.invoke(label)
            frameBuffer.clear() // Reset after success
        }
    }

    private fun loadModel(filename: String): MappedByteBuffer {
        return context.assets.openFd(filename).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }

    private fun loadLabels(filename: String): List<String> {
        return context.assets.open(filename).bufferedReader().readLines()
    }

    fun close() {
        interpreter?.close()
        isReady = false
    }
}
