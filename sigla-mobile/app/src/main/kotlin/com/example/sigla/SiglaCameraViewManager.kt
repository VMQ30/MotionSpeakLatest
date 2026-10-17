package com.example.sigla

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import java.util.concurrent.Executors

class SiglaCameraViewManager : SimpleViewManager<FrameLayout>() {

    override fun getName(): String = "SiglaCameraPreview"

    companion object {
        private const val TAG = "SiglaCameraViewManager"
        @Volatile private var activeView: FrameLayout? = null
        @Volatile private var activePreviewView: PreviewView? = null
        @Volatile private var activeOverlayView: OverlayView? = null
        @Volatile private var isFront = false
        @Volatile private var activeMode = "tap"
        @Volatile private var activeVocabulary = "words"
        @Volatile private var cameraProvider: ProcessCameraProvider? = null

        fun flipCamera() {
            setCameraFacing(!isFront)
        }

        fun setCameraFacing(front: Boolean) {
            isFront = front
            Handler(Looper.getMainLooper()).post {
                bindCamera()
            }
        }

        fun startRecognition(mode: String, vocabulary: String) {
            activeMode = mode
            activeVocabulary = vocabulary
            SiglaBridgeModule.currentInstance?.emitDetectionStatus(
                status = "Ready",
                frames = 0,
                bufferPercent = 0,
                velocity = "—",
                streak = 0,
                handsWarning = false
            )
        }

        fun stopRecognition() {
            SiglaBridgeModule.currentInstance?.emitDetectionStatus(
                status = "Idle",
                frames = 0,
                bufferPercent = 0,
                velocity = "—",
                streak = 0,
                handsWarning = false
            )
        }

        fun startTapRecording() {
            // Begins recording gesture
        }

        fun stopTapRecording() {
            // Emits final result if gesture detected
        }

        private fun bindCamera() {
            val preview = activePreviewView ?: return
            val context = preview.context
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val selector = if (isFront) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val previewUseCase = Preview.Builder().build().also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }

                    cameraProvider?.unbindAll()
                    if (context is androidx.lifecycle.LifecycleOwner) {
                        cameraProvider?.bindToLifecycle(context, selector, previewUseCase)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    override fun createViewInstance(reactContext: ThemedReactContext): FrameLayout {
        val root = FrameLayout(reactContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val preview = PreviewView(reactContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val overlay = OverlayView(reactContext, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(preview)
        root.addView(overlay)

        activeView = root
        activePreviewView = preview
        activeOverlayView = overlay

        bindCamera()

        return root
    }

    @ReactProp(name = "isFrontCamera")
    fun setIsFrontCamera(view: FrameLayout, front: Boolean) {
        if (isFront != front) {
            isFront = front
            bindCamera()
        }
    }
}
