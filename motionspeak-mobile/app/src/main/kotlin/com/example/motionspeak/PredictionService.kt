package com.example.motionspeak

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

private const val TAG = "PredictionService"
private const val SEQUENCE_LENGTH = 30
// FEATURE_SIZE is shared with HandLandmarkHelper; the authoritative declaration is
// `internal const val FEATURE_SIZE = 350` in HandLandmarkHelper.kt.
private const val NUM_CLASSES = 60
private const val PAD = 3
private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172

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
        if (frameBuffer.size > 100) frameBuffer.removeFirst()

        if (frameBuffer.size >= 15) {
            onCollecting?.invoke(frameBuffer.size / 30f)
            runInference()
        }
    }

    private fun runInference() {
        var firstActive = -1
        var lastActive = -1
        for (i in frameBuffer.indices) {
            if (frameBuffer[i][LEFT_FLAG] > 0f || frameBuffer[i][RIGHT_FLAG] > 0f) {
                if (firstActive == -1) firstActive = i
                lastActive = i
            }
        }

        if (firstActive == -1 || lastActive - firstActive < 6) return

        val start = maxOf(0, firstActive - PAD)
        val end = minOf(frameBuffer.size - 1, lastActive + PAD)
        val trimmed = frameBuffer.subList(start, end + 1).toList()

        val resampled = ArrayList<FloatArray>(30)
        for (i in 0 until 30) {
            val idx = ((i.toFloat() / 29f) * (trimmed.size - 1)).roundToInt()
            resampled.add(trimmed[idx])
        }

        val input = Array(1) { Array(30) { FloatArray(FEATURE_SIZE) } }
        for (i in 0 until 30) input[0][i] = resampled[i]

        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter?.run(input, output)

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
            frameBuffer.clear()
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
