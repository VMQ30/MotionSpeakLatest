package com.example.motionspeak

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "PredictionService"
private const val DEFAULT_SEQUENCE_LENGTH = MotionPreprocessor.SEQUENCE_LENGTH
private const val LEFT_FLAG = MotionPreprocessor.LEFT_FLAG
private const val RIGHT_FLAG = MotionPreprocessor.RIGHT_FLAG

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
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val frameBuffer = ArrayDeque<FloatArray>()
    private var inactiveStreak = 0
    private var holdFrames = 0

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
        }
    }

    fun processFrame(features: FloatArray) {
        if (!isReady) return

        val hasHands = features.size > RIGHT_FLAG && (features[LEFT_FLAG] > 0f || features[RIGHT_FLAG] > 0f)

        if (hasHands) {
            inactiveStreak = 0
            // Buffer the raw 173-dimensional features for sequence processing
            frameBuffer.addLast(features.copyOf(MotionPreprocessor.RAW_FEATURES))
            if (frameBuffer.size > 60) frameBuffer.removeFirst()

            val progress = (frameBuffer.size / 30f).coerceIn(0f, 1f)
            onCollecting?.invoke(progress)

            // Measure hand movement to detect terminal hold vs active motion
            val motionMag = if (frameBuffer.size >= 2) {
                val prev = frameBuffer[frameBuffer.size - 2]
                val curr = frameBuffer[frameBuffer.size - 1]
                kotlin.math.abs(curr[0] - prev[0]) + kotlin.math.abs(curr[1] - prev[1]) +
                kotlin.math.abs(curr[63] - prev[63]) + kotlin.math.abs(curr[64] - prev[64])
            } else 1.0f

            // A hold is detected when motion decelerates to near zero at the end of a gesture
            if (motionMag < 0.05f && frameBuffer.size >= 20) {
                holdFrames++
            } else {
                holdFrames = 0
            }

            // Fire on terminal hold (hand held still for ~260ms after executing the sign)
            if (holdFrames >= 8 && frameBuffer.size >= 22) {
                val success = runInference()
                if (success) {
                    frameBuffer.clear()
                    holdFrames = 0
                    inactiveStreak = 0
                    onCollecting?.invoke(0f)
                }
            } else if (frameBuffer.size >= 58 && holdFrames >= 4) {
                // Buffer capacity reached and motion has slowed
                val success = runInference()
                if (success) {
                    frameBuffer.clear()
                    holdFrames = 0
                    inactiveStreak = 0
                    onCollecting?.invoke(0f)
                }
            }
            // CRITICAL: If hands are still actively moving (motionMag >= 0.05f),
            // DO NOT INTERRUPT THE SIGN! Let the user finish the full motion.
        } else {
            holdFrames = 0
            inactiveStreak++
            // Hands were lowered or left the frame: natural end of gesture
            if (inactiveStreak >= 5) {
                if (frameBuffer.size >= 12) {
                    // Complete sign captured: run inference now!
                    runInference()
                }
                frameBuffer.clear()
                inactiveStreak = 0
                onCollecting?.invoke(0f)
            } else if (frameBuffer.isEmpty()) {
                onCollecting?.invoke(0f)
            }
        }
    }

    internal fun runInference(): Boolean {
        if (frameBuffer.size < 8) return false

        // 1. Fill short tracking gaps (up to 6 frames)
        val filled = MotionPreprocessor.fillGaps(frameBuffer.toList())

        // 2. Trim active frames (between first and last hand detection +/- 3 pad)
        val active = MotionPreprocessor.trimActive(filled) ?: return false

        // 3. Resample to target sequence length
        val resampled = MotionPreprocessor.resample(active, sequenceLength)

        // 4. Construct input tensor dynamically based on model specification
        MotionPreprocessor.prepareModelInput(
            resampled = resampled,
            target = inputBuffer[0],
            numFeatures = numFeatures,
            handShape = modelMeta.handShape || (numFeatures >= 299),
            motion = modelMeta.motion || (numFeatures >= 350),
            noZ = modelMeta.noZ,
            motionScale = modelMeta.motionScale
        )

        // 5. Run TFLite inference safely inside try-catch
        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed: ${e.message}", e)
            return false
        }

        var maxIdx = 0
        var maxConf = 0f
        for (i in 0 until numClasses) {
            val score = outputBuffer[0][i]
            if (score > maxConf) {
                maxConf = score
                maxIdx = i
            }
        }

        // Firing threshold: emit when confident (model achieves >95% confidence on valid signs)
        if (maxConf >= 0.70f) {
            val label = labels.getOrElse(maxIdx) { "Unknown" }
            onResult?.invoke(label)
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
        onCollecting?.invoke(0f)
    }

    fun close() {
        interpreter?.close()
        isReady = false
        frameBuffer.clear()
        inactiveStreak = 0
        holdFrames = 0
    }
}
