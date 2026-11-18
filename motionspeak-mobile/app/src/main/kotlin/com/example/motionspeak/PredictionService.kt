package com.example.motionspeak

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "PredictionService"
private const val DEFAULT_SEQUENCE_LENGTH = MotionPreprocessor.SEQUENCE_LENGTH
private const val LEFT_FLAG = MotionPreprocessor.LEFT_FLAG
private const val RIGHT_FLAG = MotionPreprocessor.RIGHT_FLAG

enum class ConfidenceTier { HIGH, MODERATE, LOW }

data class PredictionAlternative(
    val label: String,
    val confidence: Float
)

data class ModelDiagnosticInfo(
    val modelName: String,
    val isReady: Boolean,
    val sequenceLength: Int,
    val numFeatures: Int,
    val numClasses: Int,
    val activeVocabulary: String,
    val activeBundleVersion: String?,
    val inputTensorShape: List<Int>,
    val outputTensorShape: List<Int>
)

data class PredictionDecision(
    val topLabel: String,
    val confidence: Float,
    val tier: ConfidenceTier,
    val alternatives: List<PredictionAlternative>,
    val uncertaintyReason: String? = null,
    val tfliteLatencyMs: Double = 0.0,
    val finalFrameTimestampMs: Long = 0L,
    val margin: Float = 0f,
    val normalizedEntropy: Float = 0f
)

internal data class ModelMeta(
    val frames: Int = 30,
    val numFeatures: Int = 173,
    val rawFeatures: Int = 173,
    val handShape: Boolean = false,
    val motion: Boolean = false,
    val motionScale: Float = 5.0f,
    val noZ: Boolean = false,
    val labels: List<String> = emptyList()
)

class PredictionService(private val context: Context) {

    /** Which sign-language vocabulary the interpreter is running. */
    enum class Vocabulary { WORDS, LETTERS }

    companion object {
        /** Intent extra key: desired starting Vocabulary for the translator screen. */
        const val EXTRA_START_VOCABULARY = "extra_start_vocabulary"
        const val NO_HAND_TIMEOUT = 16
        const val HOLD_THRESHOLD = 18
        const val FAST_HOLD_THRESHOLD = 10
        const val MIN_POSE_FRAMES = 24
        const val SEQUENCE_LENGTH = 30
        const val MIN_SEQUENCE_MOTION = 0.50f
        const val MIN_REAL_FRAMES_FOR_FIRE = 20
        const val MIN_MOTION_FRAMES = 6
        const val INTER_SIGN_COOLDOWN_MS = 350L
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val frameBuffer = ArrayDeque<Pair<Long, FloatArray>>()
    private var inactiveStreak = 0
    private var holdFrames = 0
    private var cumulativeMotion = 0f
    private var downwardRetractionStreak = 0
    private var lastValidInferenceTimestampMs = 0L

    private var sequenceLength = DEFAULT_SEQUENCE_LENGTH
    private var numFeatures = MotionPreprocessor.RAW_FEATURES
    private var numClasses = 60
    private var modelMeta = ModelMeta()

    // Pre-allocated inference buffers sized dynamically upon model load
    private var inputBuffer = Array(1) { Array(DEFAULT_SEQUENCE_LENGTH) { FloatArray(MotionPreprocessor.RAW_FEATURES) } }
    private var outputBuffer = Array(1) { FloatArray(60) }

    /**
     * True once [init] has loaded the model and labels successfully.
     * Internal so [MainActivity] can check it without reflection.
     */
    internal var isReady = false
        private set

    var onResult: ((String) -> Unit)? = null
    var onDecision: ((PredictionDecision) -> Unit)? = null
    var onCollecting: ((Float) -> Unit)? = null

    fun init() {
        try {
            val interp = Interpreter(loadModel("motionspeak.tflite"))
            interpreter = interp

            val meta = loadMeta("meta.json")
            this.modelMeta = meta

            labels = if (meta.labels.isNotEmpty()) meta.labels else loadLabels("labels.txt")

            // Inspect the interpreter input/output shapes directly for ground truth
            val inShape = interp.getInputTensor(0).shape()
            sequenceLength = if (inShape.size > 1 && inShape[1] > 0) inShape[1] else meta.frames
            numFeatures = if (inShape.size > 2 && inShape[2] > 0) inShape[2] else meta.numFeatures

            val outShape = interp.getOutputTensor(0).shape()
            numClasses = if (outShape.size > 1 && outShape[1] > 0) outShape[1] else labels.size

            inputBuffer = Array(1) { Array(sequenceLength) { FloatArray(numFeatures) } }
            outputBuffer = Array(1) { FloatArray(numClasses) }

            isReady = true
            Log.i(TAG, "Model & Labels loaded. Input: [1, $sequenceLength, $numFeatures], Classes: $numClasses")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            if (ModelUpdateManager.hasActiveModelBundle(context)) {
                ModelUpdateManager.rollbackToLastKnownGood(context)
                try {
                    val fallbackInterp = Interpreter(loadModel("motionspeak.tflite"))
                    interpreter = fallbackInterp
                    isReady = true
                    Log.i(TAG, "Successfully recovered using fallback baseline model after rollback")
                } catch (e2: Exception) {
                    Log.e(TAG, "Baseline fallback also failed: ${e2.message}", e2)
                }
            }
        }
    }

    fun processFrame(features: FloatArray, frameCaptureTimestampMs: Long = SystemClock.elapsedRealtime()) {
        if (!isReady) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastValidInferenceTimestampMs < INTER_SIGN_COOLDOWN_MS) {
            return
        }

        val hasHands = features.size > RIGHT_FLAG && (features[LEFT_FLAG] > 0f || features[RIGHT_FLAG] > 0f)
        val spatialGate = if (hasHands) MotionPreprocessor.evaluateSpatialGating(features) else null
        val isLapResting = spatialGate?.isLapResting == true

        if (hasHands) {
            inactiveStreak = 0

            // If hands are resting on the lap and buffer is empty, do not trigger false gesture collection
            if (isLapResting && frameBuffer.isEmpty()) {
                onCollecting?.invoke(0f)
                return
            }

            // Buffer timestamp and raw 173-dimensional features for temporal sequence processing
            frameBuffer.addLast(frameCaptureTimestampMs to features.copyOf(MotionPreprocessor.RAW_FEATURES))
            if (frameBuffer.size > 90) frameBuffer.removeFirst()

            val progress = (frameBuffer.size / 30f).coerceIn(0f, 1f)
            onCollecting?.invoke(progress)

            // Measure hand movement to detect terminal hold vs active motion
            val motionMag = if (frameBuffer.size >= 2) {
                val prev = frameBuffer[frameBuffer.size - 2].second
                val curr = frameBuffer[frameBuffer.size - 1].second
                kotlin.math.abs(curr[0] - prev[0]) + kotlin.math.abs(curr[1] - prev[1]) +
                kotlin.math.abs(curr[63] - prev[63]) + kotlin.math.abs(curr[64] - prev[64])
            } else 1.0f

            cumulativeMotion += motionMag

            // A hold is detected when motion decelerates to near zero at the end of a gesture
            if (motionMag < 0.05f && frameBuffer.size >= 20) {
                holdFrames++
            } else {
                holdFrames = 0
            }

            // Detect downward retraction (hands lowering towards rest after a sign)
            val isRetracting = if (frameBuffer.size >= 2) {
                val prev = frameBuffer[frameBuffer.size - 2].second
                val curr = frameBuffer[frameBuffer.size - 1].second
                val leftDown = curr[LEFT_FLAG] > 0f && prev[LEFT_FLAG] > 0f && MotionPreprocessor.isDownwardRetraction(curr[1], prev[1])
                val rightDown = curr[RIGHT_FLAG] > 0f && prev[RIGHT_FLAG] > 0f && MotionPreprocessor.isDownwardRetraction(curr[64], prev[64])
                leftDown || rightDown
            } else false

            if (isRetracting) {
                downwardRetractionStreak++
            } else {
                downwardRetractionStreak = 0
            }

            // If hands entered lap resting state after active signing, complete the gesture immediately
            if (isLapResting && frameBuffer.size >= 16 && cumulativeMotion >= MIN_SEQUENCE_MOTION) {
                runInference(frameCaptureTimestampMs)
                frameBuffer.clear()
                holdFrames = 0
                downwardRetractionStreak = 0
                inactiveStreak = 0
                cumulativeMotion = 0f
                onCollecting?.invoke(0f)
                return
            }

            // If user completed a gesture with sufficient motion and starts downward retraction (epenthesis),
            // trigger inference on the completed gesture instead of waiting for 16 empty frames
            if (downwardRetractionStreak >= 4 && frameBuffer.size >= MIN_POSE_FRAMES && cumulativeMotion >= MIN_SEQUENCE_MOTION) {
                runInference(frameCaptureTimestampMs)
                frameBuffer.clear()
                holdFrames = 0
                downwardRetractionStreak = 0
                inactiveStreak = 0
                cumulativeMotion = 0f
                onCollecting?.invoke(0f)
                return
            }

            // Adaptive hold: If significant cumulative motion was executed (>= 0.80f), FAST_HOLD_THRESHOLD (~330ms) is sufficient!
            // Slower gestures retain the full HOLD_THRESHOLD (18 frames ~600ms) to avoid premature cutoff.
            val effectiveHoldThreshold = if (cumulativeMotion >= 0.80f) FAST_HOLD_THRESHOLD else HOLD_THRESHOLD
            if (holdFrames >= effectiveHoldThreshold && frameBuffer.size >= MIN_POSE_FRAMES && cumulativeMotion >= MIN_SEQUENCE_MOTION) {
                runInference(frameCaptureTimestampMs)
                frameBuffer.clear()
                holdFrames = 0
                downwardRetractionStreak = 0
                inactiveStreak = 0
                cumulativeMotion = 0f
                onCollecting?.invoke(0f)
            } else if (frameBuffer.size >= 85 && holdFrames >= 8 && cumulativeMotion >= MIN_SEQUENCE_MOTION) {
                // Buffer capacity reached and motion has slowed
                runInference(frameCaptureTimestampMs)
                frameBuffer.clear()
                holdFrames = 0
                downwardRetractionStreak = 0
                inactiveStreak = 0
                cumulativeMotion = 0f
                onCollecting?.invoke(0f)
            }
        } else {
            holdFrames = 0
            downwardRetractionStreak = 0
            inactiveStreak++

            // If a gesture was in progress, buffer empty frames during brief tracking glitches (< NO_HAND_TIMEOUT)
            if (frameBuffer.isNotEmpty() && inactiveStreak <= NO_HAND_TIMEOUT) {
                frameBuffer.addLast(frameCaptureTimestampMs to features.copyOf(MotionPreprocessor.RAW_FEATURES))
                if (frameBuffer.size > 90) frameBuffer.removeFirst()
            }

            // Hands were lowered or left the frame: natural end of gesture
            if (inactiveStreak >= NO_HAND_TIMEOUT) {
                if (frameBuffer.size >= 12 && cumulativeMotion >= 0.35f) {
                    runInference(frameCaptureTimestampMs)
                }
                frameBuffer.clear()
                inactiveStreak = 0
                cumulativeMotion = 0f
                onCollecting?.invoke(0f)
            } else if (frameBuffer.isEmpty()) {
                onCollecting?.invoke(0f)
            }
        }
    }

    internal fun runInference(finalFrameTimestampMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (frameBuffer.size < 8) return false

        // 1. Fill short tracking gaps with temporal alignment
        val filledTimed = MotionPreprocessor.fillGapsTimed(frameBuffer.toList())

        // 2. Trim active frames (between first and last hand detection +/- 3 pad)
        val activeTimed = MotionPreprocessor.trimActiveTimed(filledTimed) ?: run {
            onDecision?.invoke(
                PredictionDecision(
                    topLabel = "Unknown",
                    confidence = 0f,
                    tier = ConfidenceTier.LOW,
                    alternatives = emptyList(),
                    uncertaintyReason = "Sign is not recognized. Please repeat the gesture.",
                    finalFrameTimestampMs = finalFrameTimestampMs
                )
            )
            return false
        }

        // 2b. Spatial Gating: Verify active sequence entered the valid signing space (not pure lap resting)
        val hasSigningSpaceActivity = activeTimed.any { pair ->
            MotionPreprocessor.evaluateSpatialGating(pair.second).hasValidSigningHand
        }
        if (!hasSigningSpaceActivity) {
            Log.d(TAG, "Gated sequence: hands remained in resting/lap zone throughout motion")
            return false
        }

        // 3. Resample to target sequence length using physical time continuous linear interpolation (∆t)
        val resampled = MotionPreprocessor.resampleTimed(activeTimed, sequenceLength)

        // 4. Construct input tensor dynamically based on model specification
        val settings = AppSettings.getInstance(context)
        MotionPreprocessor.prepareModelInput(
            resampled = resampled,
            target = inputBuffer[0],
            numFeatures = numFeatures,
            handShape = modelMeta.handShape || (numFeatures >= 299),
            motion = modelMeta.motion || (numFeatures >= 350),
            noZ = modelMeta.noZ,
            motionScale = modelMeta.motionScale,
            signerHandScale = settings.signerHandScale,
            signerSpeedFactor = settings.signerSpeedFactor
        )

        // 5. Run TFLite inference safely inside try-catch with nanosecond profiling
        val tfliteStartNs = SystemClock.elapsedRealtimeNanos()
        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed: ${e.message}", e)
            return false
        }
        val tfliteDurationMs = (SystemClock.elapsedRealtimeNanos() - tfliteStartNs) / 1e6

        val candidates = ArrayList<Pair<Int, Float>>(numClasses)
        for (i in 0 until numClasses) {
            candidates.add(i to outputBuffer[0][i])
        }
        candidates.sortByDescending { it.second }

        val top1 = candidates.getOrNull(0) ?: return false
        val topIdx = top1.first
        val topConf = top1.second
        val topLabel = labels.getOrElse(topIdx) { "Unknown" }

        val topAlternatives = candidates.take(3).map { (idx, conf) ->
            PredictionAlternative(labels.getOrElse(idx) { "Unknown" }, conf)
        }

        val second = candidates.getOrNull(1)
        val margin = if (second != null) topConf - second.second else topConf
        val normalizedEntropy = MotionPreprocessor.computeNormalizedEntropy(outputBuffer[0])

        val tier = MotionPreprocessor.evaluateConfidenceTier(
            topConf = topConf,
            margin = margin,
            normalizedEntropy = normalizedEntropy
        )

        val pct = (topConf * 100).toInt()
        val topFormatted = topLabel.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val uncertaintyReason = when {
            tier == ConfidenceTier.LOW && topConf < MotionPreprocessor.DEFAULT_MODERATE_CONFIDENCE ->
                "<b>Gesture confidence low</b> ($pct%). Please repeat the gesture."
            tier == ConfidenceTier.LOW && margin < MotionPreprocessor.DEFAULT_MIN_MARGIN && second != null -> {
                val secondRaw = labels.getOrElse(second.first) { "Unknown" }
                val secondFormatted = secondRaw.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                "Uncertain between <b>$topFormatted</b> and <b>$secondFormatted</b> ($pct%). Tap the blinking red words to adjust."
            }
            tier == ConfidenceTier.LOW && normalizedEntropy > MotionPreprocessor.DEFAULT_MAX_ENTROPY ->
                "<b>Gesture ambiguous</b> (entropy ${(normalizedEntropy * 100).toInt()}%). Please repeat the gesture."
            tier == ConfidenceTier.MODERATE ->
                "<b>Moderate confidence</b> ($pct%). Tap the blinking red words to adjust."
            else -> null
        }

        val decision = PredictionDecision(
            topLabel = topLabel,
            confidence = topConf,
            tier = tier,
            alternatives = topAlternatives,
            uncertaintyReason = uncertaintyReason,
            tfliteLatencyMs = tfliteDurationMs,
            finalFrameTimestampMs = finalFrameTimestampMs,
            margin = margin,
            normalizedEntropy = normalizedEntropy
        )

        onDecision?.invoke(decision)

        if (tier == ConfidenceTier.HIGH || tier == ConfidenceTier.MODERATE) {
            if (onDecision == null) {
                onResult?.invoke(topLabel)
            }
            lastValidInferenceTimestampMs = SystemClock.elapsedRealtime()
            return true
        }
        return false
    }

    private fun loadMeta(filename: String): ModelMeta {
        return try {
            val jsonStr = context.assets.open(filename).bufferedReader().readText()
            val json = org.json.JSONObject(jsonStr)
            val lblArray = json.optJSONArray("labels")
            val lblList = if (lblArray != null) {
                List(lblArray.length()) { lblArray.getString(it) }
            } else emptyList()

            ModelMeta(
                frames = json.optInt("frames", 30),
                numFeatures = json.optInt("num_features", 173),
                rawFeatures = json.optInt("raw_features", 173),
                handShape = json.optBoolean("hand_shape", false),
                motion = json.optBoolean("motion", false),
                motionScale = json.optDouble("motion_scale", 5.0).toFloat(),
                noZ = json.optBoolean("no_z", false),
                labels = lblList
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not load meta.json: ${e.message}")
            ModelMeta()
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

    fun reset() {
        frameBuffer.clear()
        inactiveStreak = 0
        holdFrames = 0
        downwardRetractionStreak = 0
        cumulativeMotion = 0f
        lastValidInferenceTimestampMs = 0L
        onCollecting?.invoke(0f)
    }

    fun getModelDiagnostics(): ModelDiagnosticInfo {
        val inShape = try {
            interpreter?.getInputTensor(0)?.shape()?.toList() ?: listOf(1, sequenceLength, numFeatures)
        } catch (_: Exception) {
            listOf(1, sequenceLength, numFeatures)
        }
        val outShape = try {
            interpreter?.getOutputTensor(0)?.shape()?.toList() ?: listOf(1, numClasses)
        } catch (_: Exception) {
            listOf(1, numClasses)
        }
        return ModelDiagnosticInfo(
            modelName = "sign_model_motion.tflite",
            isReady = isReady,
            sequenceLength = sequenceLength,
            numFeatures = numFeatures,
            numClasses = numClasses,
            activeVocabulary = Vocabulary.WORDS.name,
            activeBundleVersion = ModelUpdateManager.getCachedVersion(context),
            inputTensorShape = inShape,
            outputTensorShape = outShape
        )
    }

    fun close() {
        interpreter?.close()
        isReady = false
        frameBuffer.clear()
        inactiveStreak = 0
        holdFrames = 0
        downwardRetractionStreak = 0
        cumulativeMotion = 0f
        lastValidInferenceTimestampMs = 0L
    }
}
