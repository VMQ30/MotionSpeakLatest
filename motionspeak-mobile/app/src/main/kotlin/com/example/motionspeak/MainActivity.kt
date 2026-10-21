package com.example.motionspeak

import android.Manifest
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
private const val LIVE_RESULT_VISIBLE_MS = 2000L
private const val WORD_BANK_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

internal val teardownExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "sigla-teardown").apply { isDaemon = true }
}

class MainActivity : AppCompatActivity() {

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

    private var showFilipino       = true
    private var emergencyHoldStart = 0L

    private val hideResult = Runnable { Motion.hide(binding.cardResult, View.INVISIBLE) }
    private fun scheduleResultHide() {
        binding.cardResult.removeCallbacks(hideResult)
        binding.cardResult.postDelayed(hideResult, LIVE_RESULT_VISIBLE_MS)
    }

    @Volatile private var filipinoMap: Map<String, String> = emptyMap()
    private var lastWordBankRefresh = 0L
    private var lastLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = TranslationHistoryManager.getInstance(this)
        appSettings = AppSettings.getInstance(this)
        showFilipino = appSettings.showFilipino
        isFrontCamera = appSettings.isFrontCamera

        setupComplete = true
        initTts()
        startVision()
        loadFilipinoTranslations()
        setupButtons()
        binding.btnBack.setOnClickListener { finish() }
        updateFilipinoToggleLabel()
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
        binding.cardResult.removeCallbacks(hideResult)
        Motion.hideNow(binding.cardResult, View.INVISIBLE)
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

    private fun loadFilipinoTranslations() {
        val now = SystemClock.elapsedRealtime()
        if (lastWordBankRefresh != 0L && now - lastWordBankRefresh < WORD_BANK_REFRESH_INTERVAL_MS) return
        lastWordBankRefresh = now
        lifecycleScope.launch(Dispatchers.IO) { refreshWordBank() }
    }

    private suspend fun refreshWordBank() {
        val cached = ModelUpdateManager.loadCachedWordBank(this@MainActivity) ?: emptyList()
        val words: List<WordBankWord> =
            when (val refresh = refreshWordBank(this@MainActivity, cached)) {
                is WordBankRefresh.Updated -> refresh.words
                else -> cached
            }

        val map = mutableMapOf<String, String>()
        for (word in words) {
            val translation = word.filipino_translation
            if (!translation.isNullOrBlank()) map[word.label.lowercase()] = translation
        }
        if (map.isEmpty() && filipinoMap.isNotEmpty()) return
        if (map == filipinoMap) return

        words.forEach { word ->
            if (!word.filipino_translation.isNullOrBlank()) {
                historyManager.setTranslation(word.label.lowercase(), word.filipino_translation)
            }
        }

        withContext(Dispatchers.Main) { filipinoMap = map }
    }

    private enum class StatusKind { LOADING, READY, ERROR }
    private fun setStatus(text: String, kind: StatusKind) {
        Motion.swapText(binding.tvStatus, text)
        val colour = when (kind) {
            StatusKind.LOADING -> R.color.ms_text_secondary
            StatusKind.READY -> R.color.ms_success
            StatusKind.ERROR -> R.color.ms_danger
        }
        Motion.tintTo(binding.statusDot, ContextCompat.getColor(this, colour))
    }

    private fun setupCallbacks() {
        val predictor = this.predictor ?: return
        predictor.onResult = { label -> runOnUiThread { showResult(label) } }
        predictor.onCollecting = { progress -> pendingCollectPct = (progress * 100).toInt() }
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
        val filipino = getFilipinoTranslation(label)

        if (Motion.isShowing(binding.cardResult)) {
            Motion.swapText(binding.tvResult, word)
            if (filipino != null && showFilipino) {
                Motion.swapText(binding.tvFilipinoResult, filipino)
            } else {
                Motion.hide(binding.tvFilipinoResult, View.GONE)
            }
        } else {
            binding.tvResult.text = word
            if (filipino != null && showFilipino) {
                binding.tvFilipinoResult.text = filipino
                Motion.reveal(binding.tvFilipinoResult)
            } else {
                binding.tvFilipinoResult.visibility = View.GONE
            }
            Motion.emphasize(binding.cardResult)
        }

        Haptics.confirm(binding.root)
        speak(label)
        lifecycleScope.launch(Dispatchers.IO) { historyManager.add(label, 100, "motion") }
        scheduleResultHide()
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
                val action = overlayAction(overlayShown, displayed.landmarks.isNotEmpty())
                if (action == OverlayAction.DRAW || action == OverlayAction.DRAW_AND_SHOW) {
                    binding.overlayView.setLandmarks(displayed.landmarks, displayed.sourceWidth.toFloat(), displayed.sourceHeight.toFloat(), isFrontCamera)
                }
                when (action) {
                    OverlayAction.DRAW_AND_SHOW -> showOverlay()
                    OverlayAction.HIDE -> hideOverlay()
                    OverlayAction.DRAW, OverlayAction.SKIP -> Unit
                }
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
        val pct = pendingCollectPct
        if (pct >= 0 && pct != lastShownPct) {
            lastShownPct = pct
            binding.progressBuffer.setProgress(pct, true)
            binding.tvBufferPercent.text = "$pct%"
        }
    }

    private fun showOverlay() {
        if (overlayShown) return
        overlayShown = true
        Motion.fadeTo(binding.overlayView, 1f, Motion.QUICK, Motion.ENTER)
    }

    private fun hideOverlay() {
        if (!overlayShown) return
        overlayShown = false
        Motion.fadeTo(binding.overlayView, 0f, Motion.STANDARD, Motion.EXIT) {
            if (!overlayShown) binding.overlayView.clear()
        }
    }

    private fun setupButtons() {
        binding.btnFlipCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            appSettings.isFrontCamera = isFrontCamera
            predictor?.processFrame(FloatArray(350))
            bindCamera()
        }

        binding.btnToggleFilipino.setOnClickListener {
            showFilipino = !showFilipino
            appSettings.showFilipino = showFilipino
            updateFilipinoToggleLabel()
            val translation = if (showFilipino) lastLabel?.let { getFilipinoTranslation(it) } else null
            if (translation != null) {
                binding.tvFilipinoResult.text = translation
                Motion.reveal(binding.tvFilipinoResult)
            } else {
                Motion.hide(binding.tvFilipinoResult, View.GONE)
            }
        }

        binding.btnEmergency.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    emergencyHoldStart = SystemClock.elapsedRealtime()
                    binding.btnEmergency.postDelayed(emergencyRunnable, 2000)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnEmergency.removeCallbacks(emergencyRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private val emergencyRunnable = Runnable {
        speak("Help me")
        Toast.makeText(this, "Emergency alert played", Toast.LENGTH_LONG).show()
    }

    private fun updateFilipinoToggleLabel() {
        val bg = if (showFilipino) R.color.ms_brand else R.color.ms_tint
        val fg = if (showFilipino) R.color.ms_on_brand else R.color.ms_brand_text
        Motion.tintTo(binding.btnToggleFilipino, ContextCompat.getColor(this, bg), Motion.QUICK)
        binding.btnToggleFilipino.setTextColor(ContextCompat.getColor(this, fg))
    }

    private fun getFilipinoTranslation(label: String): String? {
        return filipinoMap[label.lowercase()]
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