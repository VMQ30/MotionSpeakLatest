package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class FacialAnalysisResult(
    val affectiveEmotion: String,
    val affectiveConfidence: Float,
    val isQuestionMarker: Boolean,
    val isNegationMarker: Boolean
)

class EmotionClassifier(private val context: Context) {
    private var faceLandmarker: FaceLandmarker? = null
    private var emotionInterpreter: Interpreter? = null

    /** Latest face landmark coordinates (x,y pairs) for overlay rendering. */
    @Volatile var lastFaceLandmarks: List<FloatArray> = emptyList()
        private set

    /** Latest grammatical non-manual markers: (isQuestionMarker, isNegationMarker). */
    @Volatile var latestLinguisticMarkers: Pair<Boolean, Boolean> = Pair(false, false)
        private set


    private val emotionLabels = listOf(
        "angry", "contempt", "disgust", "fear", "happy", "neutral", "sad", "surprise"
    )

    init {
        try {
            // 1. Initialize FaceLandmarker (to find the face box)
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
            val faceOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, faceOptions)

            // 2. Initialize HSEmotion TFLite (to read the emotion)
            val file = context.assets.openFd("hsemotion_b0_fp16.tflite")
            val inputStream = FileInputStream(file.fileDescriptor)
            val fileChannel = inputStream.channel
            val mappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength
            )
            emotionInterpreter = Interpreter(mappedByteBuffer)
            fileChannel.close()
            inputStream.close()
            file.close()

            Log.i("EmotionClassifier", "Emotion pipeline loaded successfully")
        } catch (e: Exception) {
            Log.e("EmotionClassifier", "Failed to initialize emotion pipeline", e)
        }
    }

    /**
     * Takes a camera frame, finds the face, crops it, and predicts the emotion.
     * Returns a Pair of (Emotion Label, Confidence), or null if no face found.
     */
    @Synchronized
    fun predictTone(bitmap: Bitmap): Pair<String, Float>? {
        if (!AppSettings.getInstance(context).isFacialAnalysisEnabled) {
            lastFaceLandmarks = emptyList()
            latestLinguisticMarkers = Pair(false, false)
            return null
        }
        val fl = faceLandmarker ?: return null
        val interp = emotionInterpreter ?: return null

        try {
            // 1. Detect face and get bounding box
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = fl.detect(mpImage)
            val face = result.faceLandmarks().firstOrNull() ?: run {
                lastFaceLandmarks = emptyList()
                return null
            }

            // Store face landmarks for overlay rendering
            val coords = FloatArray(face.size * 2)
            for (i in face.indices) {
                coords[i * 2]     = face[i].x()
                coords[i * 2 + 1] = face[i].y()
            }
            lastFaceLandmarks = listOf(coords)

            var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
            for (lm in face) {
                if (lm.x() < minX) minX = lm.x()
                if (lm.x() > maxX) maxX = lm.x()
                if (lm.y() < minY) minY = lm.y()
                if (lm.y() > maxY) maxY = lm.y()
            }

            // Add 15% padding around the face
            val w = maxX - minX
            val h = maxY - minY
            minX = (minX - w * 0.15f).coerceAtLeast(0f)
            maxX = (maxX + w * 0.15f).coerceAtMost(1f)
            minY = (minY - h * 0.15f).coerceAtLeast(0f)
            maxY = (maxY + h * 0.15f).coerceAtMost(1f)

            // Calculate grammatical non-manual markers from facial geometry
            // Brows: landmark 70 (left), 300 (right); Upper eyelids: 159 (left), 386 (right)
            var isQuestion = false
            var isNegation = false
            if (face.size >= 400) {
                val dyLeft = kotlin.math.abs(face[159].y() - face[70].y())
                val dyRight = kotlin.math.abs(face[386].y() - face[300].y())
                val avgBrowDist = (dyLeft + dyRight) / 2f
                val faceH = (maxY - minY).coerceAtLeast(0.01f)
                val normBrow = avgBrowDist / faceH
                isQuestion = normBrow > 0.085f
                isNegation = normBrow < 0.045f
            }
            latestLinguisticMarkers = Pair(isQuestion, isNegation)

            // 2. Crop the bitmap safely within bitmap dimensions
            val left = (minX * bitmap.width).toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
            val top = (minY * bitmap.height).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
            val right = (maxX * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (maxY * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            val cropWidth = right - left
            val cropHeight = bottom - top
            if (cropWidth <= 0 || cropHeight <= 0) return null

            val croppedBmp = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

            // 3. Resize to 224x224 for HSEmotion
            val scaledBmp = Bitmap.createScaledBitmap(croppedBmp, 224, 224, true)

            // 4. Convert to ByteBuffer (NCHW format, normalized)
            val inputBuffer = ByteBuffer.allocateDirect(1 * 3 * 224 * 224 * 4).order(ByteOrder.nativeOrder())
            val pixels = IntArray(224 * 224)
            scaledBmp.getPixels(pixels, 0, 224, 0, 0, 224, 224)

            // HSEmotion expects ImageNet normalization: (pixel/255 - mean) / std
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)

            // Channels: R, G, B
            for (c in 0..2) {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = (pixel shr 16 and 0xFF) / 255.0f
                    val g = (pixel shr 8 and 0xFF) / 255.0f
                    val b = (pixel and 0xFF) / 255.0f

                    val valToAdd = when (c) {
                        0 -> (r - mean[0]) / std[0]
                        1 -> (g - mean[1]) / std[1]
                        else -> (b - mean[2]) / std[2]
                    }
                    inputBuffer.putFloat(valToAdd)
                }
            }

            // 5. Run inference
            val outputBuffer = ByteBuffer.allocateDirect(1 * 8 * 4).order(ByteOrder.nativeOrder())
            interp.run(inputBuffer, outputBuffer)

            // 6. Find max probability
            outputBuffer.rewind()
            var maxIdx = 0
            var maxProb = -1f
            for (i in 0 until 8) {
                val p = outputBuffer.float
                if (p > maxProb) {
                    maxProb = p
                    maxIdx = i
                }
            }

            // Optional: Clean up intermediate bitmaps to prevent GC lag
            if (croppedBmp != bitmap) croppedBmp.recycle()
            if (scaledBmp != croppedBmp) scaledBmp.recycle()

            return Pair(emotionLabels[maxIdx], maxProb)

        } catch (e: Exception) {
            Log.e("EmotionClassifier", "Prediction failed: ${e.message}")
            return null
        }
    }

    fun close() {
        faceLandmarker?.close()
        emotionInterpreter?.close()
    }
}
