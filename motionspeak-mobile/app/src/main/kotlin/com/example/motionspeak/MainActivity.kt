package com.example.motionspeak

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
    Thread(r, "motionspeak-teardown").apply { isDaemon = true }
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

    private var sentenceComposer: SentenceComposer? = null
    private var activeSynthesizer: SentenceSynthesizer? = null

    private val executor        = Executors.newSingleThreadExecutor()
    private val emotionExecutor = Executors.newSingleThreadExecutor { Thread(it, "motionspeak-emotion").apply { isDaemon = true } }
    private val emotionInFlight = AtomicBoolean(false)
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
    private var isSentenceState = false
    private val rawTokens = mutableListOf<String>()
    private val signedWords = mutableListOf<String>()
    private var isRenderingWords = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = TranslationHistoryManager.getInstance(this)
        appSettings = AppSettings.getInstance(this)
        isFrontCamera = appSettings.isFrontCamera

        setupComplete = true
        initTts()
        initSentenceComposer()
        startVision()
        setupButtons()
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun initSentenceComposer() {
        if (sentenceComposer != null) return
        try {
            val synthesizer = SynthesizerFactory.createSynthesizer(applicationContext, appSettings)
            activeSynthesizer = synthesizer
            if (synthesizer is LlmSynthesizer) {
                lifecycleScope.launch(Dispatchers.IO) { synthesizer.initialize() }
            }
            val composer = SentenceComposer(
                synthesizer = synthesizer,
                scope = lifecycleScope,
                pauseDurationMs = appSettings.sentencePauseDurationMs,
                autoComposeEnabled = false
            ).apply {
                currentLanguage = SentenceLanguage.fromCode(appSettings.sentenceLanguage)
                onSentenceComposed = { result -> runOnUiThread { onSentenceGenerated(result) } }
                onTokenAdded = { token, buffer -> runOnUiThread { onTokenBuffered(token, buffer) } }
                onComposingStateChanged = { isComposing -> runOnUiThread { onComposingState(isComposing) } }
            }
            sentenceComposer = composer
            Log.d(TAG, "SentenceComposer initialized successfully in initSentenceComposer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SentenceComposer: ${e.message}", e)
        }
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

                withContext(Dispatchers.Main) { initSentenceComposer() }
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

    private fun getPastelWordColor(index: Int, isNightMode: Boolean): Int {
        val darkPastels = intArrayOf(
            0xFF7DD3FC.toInt(), // Pastel Sky
            0xFF6EE7B7.toInt(), // Pastel Mint
            0xFFC4B5FD.toInt(), // Pastel Lavender
            0xFFFCA5A5.toInt(), // Pastel Peach
            0xFFFDE047.toInt(), // Pastel Buttercup
            0xFFF9A8D4.toInt(), // Pastel Rose
            0xFF5EEAD4.toInt(), // Pastel Teal
            0xFFFDBA74.toInt()  // Pastel Coral
        )
        val lightPastels = intArrayOf(
            0xFF0284C7.toInt(), // Pastel Sky Blue
            0xFF059669.toInt(), // Pastel Mint / Emerald
            0xFF7C3AED.toInt(), // Pastel Violet
            0xFFE11D48.toInt(), // Pastel Rose
            0xFFD97706.toInt(), // Pastel Amber
            0xFF0D9488.toInt(), // Pastel Teal
            0xFFC026D3.toInt(), // Pastel Fuchsia
            0xFF4F46E5.toInt()  // Pastel Indigo
        )
        val palette = if (isNightMode) darkPastels else lightPastels
        return palette[index % palette.size]
    }

    private fun renderSignedWords() {
        if (isSentenceState) return
        isRenderingWords = true
        try {
            if (signedWords.isEmpty()) {
                binding.tvMessage.setText("")
                binding.btnMakeSentence.alpha = 0.7f
                return
            }

            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val ssb = SpannableStringBuilder()

            for (i in signedWords.indices) {
                val w = signedWords[i]
                val start = ssb.length
                ssb.append(w)
                val end = ssb.length

                val color = getPastelWordColor(i, isDark)
                ssb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                if (i < signedWords.size - 1) {
                    ssb.append("  ") // distinct spacing between whole words
                }
            }

            binding.tvMessage.setText(ssb)
            val len = binding.tvMessage.text?.length ?: 0
            binding.tvMessage.setSelection(len)
            binding.btnMakeSentence.alpha = 1.0f
            binding.btnMakeSentence.isEnabled = true
            binding.scrollMessageBox.post {
                binding.scrollMessageBox.fullScroll(View.FOCUS_DOWN)
            }
        } finally {
            isRenderingWords = false
        }
    }

    private fun deleteLastSignedWord() {
        if (signedWords.isNotEmpty()) {
            signedWords.removeAt(signedWords.size - 1)
            if (rawTokens.isNotEmpty()) {
                rawTokens.removeAt(rawTokens.size - 1)
            }
            lastEmittedWord = rawTokens.lastOrNull()
            sentenceComposer?.removeLastToken()
            Haptics.tick(binding.root)
            renderSignedWords()
        } else {
            rawTokens.clear()
            lastEmittedWord = null
            sentenceComposer?.reset()
            isRenderingWords = true
            try {
                binding.tvMessage.setText("")
            } finally {
                isRenderingWords = false
            }
        }
    }

    private fun showResult(label: String) {
        // While dialogue box is in sentence state, the text CANNOT be modified by new signs
        if (isSentenceState) {
            return
        }
        lastLabel = label

        // Avoid rapid duplicates of the same word within 1 second
        val now = SystemClock.elapsedRealtime()
        if (now - lastResultTime < 1000L && label == lastEmittedWord) {
            return
        }
        lastResultTime = now
        lastEmittedWord = label

        val word = label.replace("_", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { char -> char.uppercase() }
        }

        signedWords.add(word)
        rawTokens.add(label)

        val composer = sentenceComposer
        if (composer != null && appSettings.isLlmSynthesisEnabled) {
            composer.addToken(label)
        }

        renderSignedWords()

        Haptics.confirm(binding.root)
        speak(word)
        lifecycleScope.launch(Dispatchers.IO) { historyManager.add(label, 100, "motion") }
    }

    private fun onTokenBuffered(token: String, buffer: List<String>) {
        if (isSentenceState) {
            return
        }
        binding.btnMakeSentence.isEnabled = true
        binding.btnMakeSentence.text = "Turn into sentence"
        binding.btnMakeSentence.alpha = 1.0f
    }

    private fun onComposingState(isComposing: Boolean) {
        binding.btnMakeSentence.isEnabled = !isComposing
        binding.btnMakeSentence.text = if (isComposing) "Composing…" else "Turn into sentence"
        binding.btnMakeSentence.alpha = if (isComposing) 0.8f else 1.0f
    }

    private fun onSentenceGenerated(result: SentenceResult) {
        if (result.text.isBlank()) return
        setSentenceState(true)
        isRenderingWords = true
        try {
            signedWords.clear()
            rawTokens.clear()
            binding.tvMessage.setText(result.text)
            val len = binding.tvMessage.text?.length ?: 0
            binding.tvMessage.setSelection(len)
        } finally {
            isRenderingWords = false
        }
        binding.btnMakeSentence.isEnabled = true
        binding.btnMakeSentence.text = "Turn into sentence"
        binding.btnMakeSentence.alpha = 1.0f
        binding.scrollMessageBox.post {
            binding.scrollMessageBox.fullScroll(View.FOCUS_DOWN)
        }
        Haptics.confirm(binding.root)
        Toast.makeText(this, "Sentence: ${result.text}", Toast.LENGTH_SHORT).show()
        speak(result.text)
        lifecycleScope.launch(Dispatchers.IO) {
            historyManager.add(
                result.text,
                100,
                "motion",
                rawTokens = result.originalTokens.joinToString(", ")
            )
        }
    }

    private fun setSentenceState(isSentence: Boolean) {
        if (isSentenceState == isSentence) return
        isSentenceState = isSentence
        if (isSentence) {
            binding.siriBorderView.visibility = View.VISIBLE
            binding.siriBorderView.startAnimation()
            binding.cardMessageBox.strokeWidth = 0
        } else {
            binding.siriBorderView.stopAnimation()
            binding.siriBorderView.visibility = View.GONE
            val density = resources.displayMetrics.density
            binding.cardMessageBox.strokeWidth = (1f * density).toInt()
        }
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
                    mirrored = false,
                    slots = displayed.handSlots
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
            landmarker?.reset()
            bindCamera()
        }

        binding.btnBackspace.setOnClickListener {
            if (isSentenceState) {
                // When LLM is used, treated per character
                val editable = binding.tvMessage.text
                if (!editable.isNullOrEmpty()) {
                    val start = binding.tvMessage.selectionStart
                    val end = binding.tvMessage.selectionEnd
                    if (start != end && start >= 0 && end >= 0) {
                        val min = minOf(start, end)
                        val max = maxOf(start, end)
                        editable.delete(min, max)
                        binding.tvMessage.setSelection(min.coerceIn(0, editable.length))
                    } else if (start > 0) {
                        editable.delete(start - 1, start)
                        binding.tvMessage.setSelection((start - 1).coerceIn(0, editable.length))
                    } else {
                        val len = editable.length
                        editable.delete(len - 1, len)
                        binding.tvMessage.setSelection((len - 1).coerceIn(0, editable.length))
                    }
                    Haptics.tick(binding.root)
                }
                if (binding.tvMessage.text.isNullOrBlank()) {
                    lastEmittedWord = null
                    signedWords.clear()
                    rawTokens.clear()
                    sentenceComposer?.reset()
                    setSentenceState(false)
                }
            } else {
                // When not yet using LLM, treated as a whole word
                deleteLastSignedWord()
            }
        }

        binding.tvMessage.addTextChangedListener(object : android.text.TextWatcher {
            private var prevLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                prevLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                if (isRenderingWords) return
                val currentLen = s?.length ?: 0
                val hasText = !s.isNullOrBlank()
                binding.btnMakeSentence.alpha = if (hasText) 1.0f else 0.7f

                if (!hasText) {
                    lastEmittedWord = null
                    signedWords.clear()
                    rawTokens.clear()
                    sentenceComposer?.reset()
                    setSentenceState(false)
                    return
                }

                if (!isSentenceState && !isRenderingWords && currentLen < prevLength) {
                    // Backspace was pressed on keyboard while in raw words mode:
                    // Must delete as a whole word, not per character!
                    deleteLastSignedWord()
                }
            }
        })

        binding.btnMakeSentence.setOnClickListener {
            Log.d(TAG, "btnMakeSentence pressed! tvMessage='${binding.tvMessage.text}'")
            if (sentenceComposer == null) {
                initSentenceComposer()
            }
            val composer = sentenceComposer
            if (composer == null) {
                Toast.makeText(this, "Sentence engine initializing… please try again", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!appSettings.isLlmSynthesisEnabled) {
                Toast.makeText(this, "Sentence synthesis is disabled in Settings", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = binding.tvMessage.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please sign words first before turning them into a sentence", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Haptics.confirm(binding.root)
            binding.btnMakeSentence.isEnabled = false
            binding.btnMakeSentence.text = "Composing…"

            // Extract tokens from rawTokens if available, otherwise fallback from text
            val tokensToCompose = if (rawTokens.isNotEmpty()) {
                rawTokens.toList()
            } else {
                text.lowercase()
                    .replace("[^a-z0-9_\\s]".toRegex(), "")
                    .split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
            }

            composer.triggerComposition(tokensToCompose)
        }

        binding.btnClearMessage.setOnClickListener {
            signedWords.clear()
            rawTokens.clear()
            binding.tvMessage.setText("")
            lastEmittedWord = null
            sentenceComposer?.reset()
            setSentenceState(false)
            binding.btnMakeSentence.isEnabled = true
            binding.btnMakeSentence.text = "Turn into sentence"
            binding.btnMakeSentence.alpha = 0.7f
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
            val prepared        = prepareBitmap(bitmap, rotationDegrees, isFrontCamera)
            if (prepared != bitmap) {
                bitmap.recycle()
            }

            // --- EMOTION PIPELINE ---
            val now = System.currentTimeMillis()
            if (now - lastEmotionCheckTime > 500L) {
                lastEmotionCheckTime = now
                val classifier = emotionClassifier
                if (classifier != null && emotionInFlight.compareAndSet(false, true)) {
                    emotionExecutor.execute {
                        try {
                            val result = classifier.predictTone(prepared)
                            if (result != null) {
                                val emotionLabel = result.first.replaceFirstChar { it.uppercase() }
                                val tone = ToneMapper.fromEmotion(emotionLabel)
                                sentenceComposer?.currentTone = SentenceTone.fromToneString(tone)
                                Log.i("MotionSpeak", "Emotion: $emotionLabel -> Tone: $tone")
                                runOnUiThread {
                                    binding.overlayView.setDetectedEmotion(emotionLabel)
                                }
                            } else {
                                sentenceComposer?.currentTone = SentenceTone.NEUTRAL
                                runOnUiThread {
                                    binding.overlayView.setDetectedEmotion(null)
                                }
                            }
                        } finally {
                            emotionInFlight.set(false)
                        }
                    }
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

    private fun prepareBitmap(bitmap: Bitmap, rotationDegrees: Int, isFront: Boolean = false): Bitmap {
        if (rotationDegrees == 0 && !isFront) return bitmap
        val matrix = frameMatrix.apply {
            reset()
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (isFront) {
                postScale(-1f, 1f)
            }
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

    override fun onResume() {
        super.onResume()
        sentenceComposer?.apply {
            currentLanguage = SentenceLanguage.fromCode(appSettings.sentenceLanguage)
            pauseDurationMs = appSettings.sentencePauseDurationMs
        }
        binding.btnMakeSentence.visibility = if (appSettings.isLlmSynthesisEnabled) View.VISIBLE else View.GONE
        val hasText = binding.tvMessage.text.isNotBlank()
        binding.btnMakeSentence.alpha = if (hasText) 1.0f else 0.7f
    }

    override fun onStart() { super.onStart(); startVision() }
    override fun onStop() { stopVision(); super.onStop() }
    override fun onDestroy() {
        executor.shutdown()
        emotionExecutor.shutdown()
        stopVision()
        val doomedComposer = sentenceComposer
        sentenceComposer = null
        doomedComposer?.close()

        val doomedSynthesizer = activeSynthesizer
        activeSynthesizer = null
        if (doomedSynthesizer != null) teardownExecutor.execute {
            kotlinx.coroutines.runBlocking { doomedSynthesizer.close() }
        }

        speech?.release()
        speech = null
        super.onDestroy()
    }
}
