package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Manages CameraX preview lifecycle, image analysis pipelines, and frame transformation.
 * Decouples low-level video capture and hardware bindings from recognition and UI presentation.
 */
class CameraSessionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFramePrepared: (Bitmap, Long) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "motionspeak-camera-analysis").apply { isDaemon = true } }
    private val frameMatrix = Matrix()

    var isFrontCamera: Boolean = false
    private var isActive = false

    @Volatile var latestCameraToBitmapMs: Double = 0.0
        private set

    /**
     * Kept for backwards compatibility with vision pipeline listeners.
     * Backpressure is managed directly by CameraX STRATEGY_KEEP_ONLY_LATEST.
     */
    fun onFrameCompleted() {
        // No-op: CameraX STRATEGY_KEEP_ONLY_LATEST manages frame flow natively
    }

    fun start() {
        isActive = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isActive) return@addListener
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    fun bindCamera() {
        val provider = cameraProvider ?: return
        CaptureRateMonitor.reset()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        previewView.scaleX = 1f
        previewView.scaleY = 1f
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(480, 360))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .build()

        var frameCounter = 0L

        analysis.setAnalyzer(executor) { imageProxy ->
            if (!isActive) {
                imageProxy.close()
                return@setAnalyzer
            }

            CaptureRateMonitor.onFrame()
            val captureTimestampMs = SystemClock.elapsedRealtime()

            try {
                val startConversionNs = SystemClock.elapsedRealtimeNanos()
                val bitmap = imageProxy.toBitmap()
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val prepared = prepareBitmap(bitmap, rotationDegrees, isFrontCamera)
                if (prepared != bitmap) {
                    bitmap.recycle()
                }

                val conversionMs = (SystemClock.elapsedRealtimeNanos() - startConversionNs) / 1e6
                latestCameraToBitmapMs = conversionMs

                frameCounter++
                // Sample photometric illuminance every 6 frames without blocking analysis
                if (frameCounter % 6L == 0L) {
                    PhotometricLuxEstimator.estimatePhotometricLux(prepared)
                }

                onFramePrepared(prepared, captureTimestampMs)
            } catch (e: Exception) {
                Log.e("CameraSessionManager", "Frame analysis failed: ${e.message}", e)
            } finally {
                imageProxy.close()
            }
        }

        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e("CameraSessionManager", "Camera bind failed: ${e.message}")
        }
    }

    private fun prepareBitmap(bitmap: Bitmap, rotationDegrees: Int, isFront: Boolean): Bitmap {
        if (rotationDegrees == 0 && !isFront) return bitmap
        val matrix = frameMatrix.apply {
            reset()
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
            if (isFront) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun stop() {
        isActive = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w("CameraSessionManager", "Error unbinding camera: ${e.message}")
        }
    }

    fun release() {
        stop()
        executor.shutdown()
    }
}
