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

class EmotionClassifier(private val context: Context) {
    private var faceLandmarker: FaceLandmarker? = null
    private var emotionInterpreter: Interpreter? = null

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

            Log.i("EmotionClassifier", "Emotion pipeline loaded successfully")
        } catch (e: Exception) {
            Log.e("EmotionClassifier", "Failed to initialize emotion pipeline", e)
        }
    }

    /**
     * Takes a camera frame, finds the face, crops it, and predicts the emotion.
     * Returns a Pair of (Emotion Label, Confidence), or null if no face found.
     */
    fun predictTone(bitmap: Bitmap): Pair<String, Float>? {
        val fl = faceLandmarker ?: return null
        val interp = emotionInterpreter ?: return null

        try {
            // 1. Detect face and get bounding box
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = fl.detect(mpImage)
            val face = result.faceLandmarks().firstOrNull() ?: return null

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

            // 2. Crop the bitmap
            val left = (minX * bitmap.width).toInt()
            val top = (minY * bitmap.height).toInt()
            val right = (maxX * bitmap.width).toInt()
            val bottom = (maxY * bitmap.height).toInt()
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