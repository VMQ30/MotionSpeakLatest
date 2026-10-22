package com.example.motionspeak

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.motionspeak.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MainActivity"
private const val CAMERA_PERMISSION = 100

internal val teardownExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "sigla-teardown").apply { isDaemon = true }
}

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Intent extra key: the vocabulary ("words" or "letters") the translator
         * should open on. Sent by WordDetailActivity's "Try it yourself" button.
         */
        const val EXTRA_START_VOCABULARY = PredictionService.EXTRA_START_VOCABULARY
    }

    private lateinit var binding   : ActivityMainBinding
    private var predictor : PredictionService? = null
    private var landmarker: HandLandmarkHelper? = null

    private var emotionClassifier: EmotionClassifier? = null
    private var lastEmotionCheckTime = 0L

    private lateinit var historyManager: TranslationHistoryManager
    private lateinit var appSettings: AppSettings
    private var speech: SpeechPlayer? = null

    private val executor      = Executors.newSingleThreadExecutor()
    private var isFrontCamera = false
    private var cameraProvider: ProcessCameraProvider? = null

    private var visionActive = false
    private var setupComplete = false
    private var modelInitJob: Job? = null
    private var landmarkSink: ((LandmarkResult) -> Unit)? = null
    private val frameMatrix = Matrix()

    @Volatile private var pendingCollectPct    = -1
    @Volatile private var pendingOverlayResult: LandmarkResult? = null
    private var overlayShown = true
    private val overlayPostPending = AtomicBoolean(false)
    private var lastShownPct    = -1

    private var lastResultTime = 0L
    private var lastEmittedWord: String? = null
    private var lastLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = TranslationHistoryManager.getInstance(this)
        appSettings = AppSettings.getInstance(this)
        isFrontCamera = appSettings.isFrontCamera

        setupComplete = true
        initTts()
        startVision()
        setupButtons()
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun startVision() {
        if (visionActive || !setupComplete) return
        visionActive = true

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission()

        modelInitJob = lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { setStatus("Starting camera…", StatusKind.LOADING) }
            try {
                withContext(Dispatchers.Main) { setStatus("Loading hand tracking…", StatusKind.LOADING) }

                val sink: (LandmarkResult) -> Unit = { result ->
                    predictor?.processFrame(result.features)
                    enqueueOverlayUpdate(result)
                }
                landmarkSink = sink

                val helper = HandLandmarkHelper.Cache.acquire(applicationContext, sink)
                emotionClassifier = EmotionClassifier(applicationContext)

                if (!isActive) {
                    HandLandmarkHelper.Cache.release(sink)
                    return@launch
                }

                val service = PredictionService(applicationContext)

                val published = withContext(Dispatchers.Main) {
                    if (!visionActive) {
                        HandLandmarkHelper.Cache.release(sink)
                        service.close()
                        false
                    } else {
                        landmarker = helper
                        predictor  = service
                        setupCallbacks()
                        true
                    }
                }
                if (!published) return@launch

                withContext(Dispatchers.Main) { setStatus("Loading words…", StatusKind.LOADING) }
                service.init()
                withContext(Dispatchers.Main) { onPredictorLoaded(service) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error: ${e.message}", StatusKind.ERROR)
                    Log.e(TAG, "Model error: ${e.message}", e)
                }
            }
        }
    }

    private fun stopVision() {
        if (!visionActive) return
        visionActive = false
        pendingOverlayResult = null

        modelInitJob?.cancel()
        modelInitJob = null
        cameraProvider?.unbindAll()
        cameraProvider = null

        HandLandmarkHelper.Cache.release(landmarkSink)
        landmarkSink = null
        landmarker = null

        val doomedPredictor = predictor
        predictor = null
        if (doomedPredictor != null) teardownExecutor.execute { doomedPredictor.close() }

        emotionClassifier?.close()
        emotionClassifier = null
    }

    private fun initTts() { speech = SpeechPlayer(this, appSettings, lifecycleScope) }
    private fun speak(text: String) { speech?.speak(text) }

    private enum class StatusKind { LOADING, READY, ERROR }
    private fun setStatus(text: String, kind: StatusKind) {
        Motion.swapText(binding.tvStatus, text)
        when (kind) {
            StatusKind.LOADING -> {
                binding.statusSpinner.visibility = View.VISIBLE
                binding.statusDot.visibility = View.GONE
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_text_secondary))
            }
            StatusKind.READY -> {
                binding.statusSpinner.visibility = View.GONE
                binding.statusDot.visibility = View.VISIBLE
                Motion.tintTo(binding.statusDot, ContextCompat.getColor(this, R.color.ms_success))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_status_ready_text))
            }
            StatusKind.ERROR -> {
                binding.statusSpinner.visibility = View.GONE
                binding.statusDot.visibility = View.VISIBLE
                Motion.tintTo(binding.statusDot, ContextCompat.getColor(this, R.color.ms_danger))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_status_not_ready_text))
            }
        }
    }

    private fun setupCallbacks() {
        val predictor = this.predictor ?: return
        predictor.onResult = { label -> runOnUiThread { showResult(label) } }
        predictor.onCollecting = { progress ->
            val pct = (progress.coerceIn(0f, 1f) * 100).toInt().coerceIn(0, 100)
            pendingCollectPct = pct
            runOnUiThread {
                if (pct != lastShownPct) {
                    lastShownPct = pct
                    binding.progressBuffer.setProgress(pct, true)
                    binding.tvBufferPercent.text = "$pct%"
                }
            }
        }
    }

    private fun onPredictorLoaded(service: PredictionService) {
        if (service.isReady) {
            setStatus("Ready", StatusKind.READY)
            Log.d(TAG, "Model ready")
        } else {
            setStatus("Couldn't load the words", StatusKind.ERROR)
        }
    }

    private fun showResult(label: String) {
        lastLabel = label
        val word = label.replace("_", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { char -> char.uppercase() }
        }
        val textToAppend = word

        // Avoid rapid duplicates of the same word within 1 second
        val now = SystemClock.elapsedRealtime()
        if (now - lastResultTime < 1000L && label == lastEmittedWord) {
            return
        }
        lastResultTime = now
        lastEmittedWord = label

        val current = binding.tvMessage.text.toString().trim()
        val updated = if (current.isEmpty()) textToAppend else "$current $textToAppend"

        binding.tvMessage.text = updated

        binding.scrollMessageBox.post {
            binding.scrollMessageBox.fullScroll(View.FOCUS_DOWN)
        }

        Haptics.confirm(binding.root)
        speak(textToAppend)
        lifecycleScope.launch(Dispatchers.IO) { historyManager.add(label, 100, "motion") }
    }

    private fun enqueueOverlayUpdate(result: LandmarkResult) {
        pendingOverlayResult = result
        if (overlayPostPending.compareAndSet(false, true)) {
            runOnUiThread { drainOverlayUpdate() }
        }
    }

    private fun drainOverlayUpdate() {
        val displayed = pendingOverlayResult
        try {
            if (visionActive && displayed != null) {
                val faceLms = if (displayed.faceLandmarks.isNotEmpty()) displayed.faceLandmarks else (emotionClassifier?.lastFaceLandmarks ?: emptyList())
                binding.overlayView.visibility = View.VISIBLE
                binding.overlayView.alpha = 1f
                binding.overlayView.setLandmarks(
                    displayed.landmarks,
                    faceLms,
                    displayed.sourceWidth.toFloat(),
                    displayed.sourceHeight.toFloat(),
                    isFrontCamera
                )
                flushCollectingState()
            }
        } finally {
            overlayPostPending.set(false)
            if (pendingOverlayResult !== displayed && overlayPostPending.compareAndSet(false, true)) {
                binding.root.post { drainOverlayUpdate() }
            }
        }
    }

    private fun flushCollectingState() {
        val pct = pendingCollectPct.coerceIn(0, 100)
        if (pct != lastShownPct) {
            lastShownPct = pct
            binding.progressBuffer.setProgress(pct, true)
            binding.tvBufferPercent.text = "$pct%"
        }
    }

    private fun showOverlay() {
        binding.overlayView.visibility = View.VISIBLE
        binding.overlayView.alpha = 1f
    }

    private fun hideOverlay() {
        binding.overlayView.clear()
    }

    private fun setupButtons() {
        binding.btnFlipCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            appSettings.isFrontCamera = isFrontCamera
            predictor?.reset()
            bindCamera()
        }

        binding.btnClearMessage.setOnClickListener {
            binding.tvMessage.text = ""
            lastEmittedWord = null
        }

        binding.btnCopyMessage.setOnClickListener {
            val text = binding.tvMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MotionSpeak translation", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSpeakMessage.setOnClickListener {
            val text = binding.tvMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                speak(text)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!visionActive) return@addListener
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        CaptureRateMonitor.reset()

        val previewBuilder = Preview.Builder()
        val preview = previewBuilder.build().also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }

        binding.cameraPreview.scaleX = 1f
        binding.cameraPreview.scaleY = 1f
        binding.cameraPreview.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(480, 360))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            val lm = landmarker
            if (lm == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            CaptureRateMonitor.onFrame()
            val bitmap          = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val prepared        = prepareBitmap(bitmap, rotationDegrees)

            // --- EMOTION PIPELINE ---
            val now = System.currentTimeMillis()
            if (now - lastEmotionCheckTime > 500L) {
                lastEmotionCheckTime = now
                val classifier = emotionClassifier
                if (classifier != null) {
                    Thread {
                        val result = classifier.predictTone(prepared)
                        if (result != null) {
                            val emotionLabel = result.first
                            val tone = ToneMapper.fromEmotion(emotionLabel)
                            Log.i("MotionSpeak", "Emotion: $emotionLabel -> Tone: $tone")
                        }
                    }.start()
                }
            }
            // ------------------------------

            lm.detectAsync(prepared, SystemClock.elapsedRealtime())
            imageProxy.close()
        }

        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed: ${e.message}")
        }
    }

    private fun prepareBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = frameMatrix.apply {
            reset()
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun requestCameraPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() { super.onStart(); startVision() }
    override fun onStop() { stopVision(); super.onStop() }
    override fun onDestroy() {
        executor.shutdown()
        stopVision()
        speech?.release()
        speech = null
        super.onDestroy()
    }
}
