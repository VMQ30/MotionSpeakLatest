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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.UpdateAppearance
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
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


data class DialogueWord(
    val id: Long = System.nanoTime(),
    var displayWord: String,
    var rawLabel: String,
    var tier: ConfidenceTier,
    var confidence: Float,
    val alternatives: List<PredictionAlternative> = emptyList(),
    val uncertaintyReason: String? = null
)

class GlowingRedBlinkSpan(
    private val getBlinkFraction: () -> Float,
    private val density: Float
) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val fraction = getBlinkFraction()
        val red = 239
        val green = (68 * (0.6f + 0.4f * (1f - fraction))).toInt().coerceIn(0, 255)
        val blue = (68 * (0.6f + 0.4f * (1f - fraction))).toInt().coerceIn(0, 255)
        tp.color = Color.rgb(red, green, blue)
        tp.isFakeBoldText = true

        val glowRadius = (4f + 8f * fraction) * density
        val glowAlpha = (100 + (120 * fraction)).toInt().coerceIn(0, 255)
        tp.setShadowLayer(glowRadius, 0f, 0f, Color.argb(glowAlpha, 239, 68, 68))
    }
}

class UncertainWordClickableSpan(
    val wordEntry: DialogueWord,
    private val onClick: (DialogueWord) -> Unit
) : ClickableSpan() {
    override fun onClick(widget: View) {
        onClick(wordEntry)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.isUnderlineText = false
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Intent extra key: the vocabulary ("words" or "letters") the translator
         * should open on. Sent by WordDetailActivity's "Try it yourself" button.
         */
        const val EXTRA_START_VOCABULARY = PredictionService.EXTRA_START_VOCABULARY
        const val SLOT_CANONICALIZATION_ENABLED = false
        const val CHIRALITY_RIGHT_IS_NEGATIVE_CROSS = true
        const val MIN_ACCEPTABLE_FPS = 24
        const val FORCE_BACK_CAMERA_ONLY = false
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
    private var cameraSessionManager: CameraSessionManager? = null

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
    private val dialogueWords = ArrayList<DialogueWord>()
    private var isRenderingWords = false
    private var blinkAnimator: ValueAnimator? = null
    private var currentBlinkFraction = 0f
    private var reviewDialog: Dialog? = null
    private var choicesDialog: Dialog? = null
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceCurrentIntervalMs = 220L
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (binding.tvMessage.text.isNullOrEmpty()) {
                backspaceHandler.removeCallbacks(this)
                return
            }
            performBackspace()
            // Rapidly accelerates word deletion the longer backspace is held down
            backspaceCurrentIntervalMs = maxOf(45L, (backspaceCurrentIntervalMs * 0.82).toLong())
            backspaceHandler.postDelayed(this, backspaceCurrentIntervalMs)
        }
    }
    private var statusDotAnimator: ValueAnimator? = null

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
        cameraSessionManager?.stop()

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

    private fun startStatusDotBlink() {
        if (statusDotAnimator != null && statusDotAnimator!!.isRunning) return
        statusDotAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 750L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                binding.statusDot.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun stopStatusDotBlink() {
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        binding.statusDot.alpha = 1.0f
    }

    private enum class StatusKind { LOADING, READY, ERROR }
    private fun setStatus(text: String, kind: StatusKind) {
        Motion.swapText(binding.tvStatus, text)
        when (kind) {
            StatusKind.LOADING -> {
                stopStatusDotBlink()
                binding.statusSpinner.visibility = View.VISIBLE
                binding.statusDot.visibility = View.GONE
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_on_dark))
            }
            StatusKind.READY -> {
                binding.statusSpinner.visibility = View.GONE
                binding.statusDot.visibility = View.VISIBLE
                Motion.tintTo(binding.statusDot, ContextCompat.getColor(this, R.color.ms_success))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_on_dark))
                startStatusDotBlink()
            }
            StatusKind.ERROR -> {
                stopStatusDotBlink()
                binding.statusSpinner.visibility = View.GONE
                binding.statusDot.visibility = View.VISIBLE
                Motion.tintTo(binding.statusDot, ContextCompat.getColor(this, R.color.ms_danger))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ms_danger))
            }
        }
    }

    private fun setUncertaintyBannerHtml(htmlText: String) {
        binding.tvUncertaintyBanner.text = androidx.core.text.HtmlCompat.fromHtml(
            htmlText,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.tvUncertaintyBanner.visibility = View.VISIBLE
    }

    private fun setupCallbacks() {
        val predictor = this.predictor ?: return
        var inferenceStartMs = 0L

        predictor.onDecision = onDecision@{ decision ->
            if (isSentenceState) {
                return@onDecision
            }
            val latencyMs = if (inferenceStartMs > 0L) (SystemClock.elapsedRealtime() - inferenceStartMs) else 20L
            AnalyticsManager.getInstance(applicationContext).recordPrediction(decision, latencyMs)

            runOnUiThread {
                when (decision.tier) {
                    ConfidenceTier.HIGH -> {
                        binding.tvUncertaintyBanner.visibility = View.GONE
                        showResult(decision.topLabel, decision)
                    }
                    ConfidenceTier.MODERATE -> {
                        val pct = (decision.confidence * 100).toInt()
                        val htmlReason = decision.uncertaintyReason
                            ?: "<b>Moderate confidence</b> ($pct%). Tap the blinking red words to adjust."
                        setUncertaintyBannerHtml(htmlReason)
                        showResult(decision.topLabel, decision)
                    }
                    ConfidenceTier.LOW -> {
                        Haptics.reject(binding.root)
                        val pct = (decision.confidence * 100).toInt()
                        val htmlReason = decision.uncertaintyReason
                            ?: "<b>Gesture confidence low</b> ($pct%). Please repeat the gesture."
                        setUncertaintyBannerHtml(htmlReason)
                    }
                }
            }
        }

        predictor.onCollecting = onCollecting@{ progress ->
            if (isSentenceState) {
                return@onCollecting
            }
            if (progress > 0.05f && inferenceStartMs == 0L) {
                inferenceStartMs = SystemClock.elapsedRealtime()
            } else if (progress <= 0f) {
                inferenceStartMs = 0L
            }
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
            if (dialogueWords.isEmpty()) {
                binding.tvMessage.setText("")
                binding.btnMakeSentence.alpha = 1.0f
                updateBlinkAnimationState()
                updateUncertainWordsBadge()
                return
            }

            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val ssb = SpannableStringBuilder()
            val density = resources.displayMetrics.density

            for (i in dialogueWords.indices) {
                val entry = dialogueWords[i]
                val start = ssb.length
                ssb.append(entry.displayWord)
                val end = ssb.length

                if (entry.tier == ConfidenceTier.MODERATE) {
                    val blinkSpan = GlowingRedBlinkSpan({ currentBlinkFraction }, density)
                    val clickSpan = UncertainWordClickableSpan(entry) { clickedEntry ->
                        showAlternativesPickerForWord(clickedEntry)
                    }
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(blinkSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val color = getPastelWordColor(i, isDark)
                    ssb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                if (i < dialogueWords.size - 1) {
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
            updateBlinkAnimationState()
            updateUncertainWordsBadge()
        }
    }

    private fun updateBlinkAnimationState() {
        val hasModerateWords = dialogueWords.any { it.tier == ConfidenceTier.MODERATE } && !isSentenceState
        if (hasModerateWords) {
            if (blinkAnimator == null || !blinkAnimator!!.isRunning) {
                blinkAnimator = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
                    duration = 850L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        currentBlinkFraction = anim.animatedValue as Float
                        binding.tvMessage.invalidate()
                    }
                    start()
                }
            }
        } else {
            blinkAnimator?.cancel()
            blinkAnimator = null
        }
    }

    private fun updateUncertainWordsBadge() {
        val count = dialogueWords.count { it.tier == ConfidenceTier.MODERATE }
        if (count > 0 && !isSentenceState) {
            binding.badgeUncertainWords.visibility = View.VISIBLE
            binding.tvUncertainWordsCount.text = if (count == 1) "1 low conf" else "$count low conf"
        } else {
            binding.badgeUncertainWords.visibility = View.GONE
        }
    }

    private fun performBackspace() {
        if (isSentenceState) {
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
                dialogueWords.clear()
                signedWords.clear()
                rawTokens.clear()
                sentenceComposer?.reset()
                updateBlinkAnimationState()
                updateUncertainWordsBadge()
            }
        } else {
            deleteLastSignedWord()
        }
    }

    private fun deleteLastSignedWord() {
        if (dialogueWords.isNotEmpty()) {
            dialogueWords.removeAt(dialogueWords.size - 1)
            if (signedWords.isNotEmpty()) signedWords.removeAt(signedWords.size - 1)
            if (rawTokens.isNotEmpty()) rawTokens.removeAt(rawTokens.size - 1)
            lastEmittedWord = rawTokens.lastOrNull()
            sentenceComposer?.removeLastToken()
            Haptics.tick(binding.root)
            renderSignedWords()
        } else {
            dialogueWords.clear()
            signedWords.clear()
            rawTokens.clear()
            lastEmittedWord = null
            sentenceComposer?.reset()
            isRenderingWords = true
            try {
                binding.tvMessage.setText("")
            } finally {
                isRenderingWords = false
            }
            updateBlinkAnimationState()
            updateUncertainWordsBadge()
        }
    }

    private fun showResult(label: String, decision: PredictionDecision? = null) {
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

        val tier = decision?.tier ?: ConfidenceTier.HIGH
        val conf = decision?.confidence ?: 1.0f
        val alts = decision?.alternatives ?: emptyList()
        val reason = decision?.uncertaintyReason

        val wordEntry = DialogueWord(
            displayWord = word,
            rawLabel = label,
            tier = tier,
            confidence = conf,
            alternatives = alts,
            uncertaintyReason = reason
        )

        dialogueWords.add(wordEntry)
        signedWords.add(word)
        rawTokens.add(label)

        val composer = sentenceComposer
        if (composer != null && appSettings.isLlmSynthesisEnabled) {
            composer.addToken(label)
        }

        renderSignedWords()

        Haptics.confirm(binding.root)
        speak(word)
        lifecycleScope.launch(Dispatchers.IO) { historyManager.add(label, (conf * 100).toInt(), "motion") }
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
        binding.btnMakeSentence.alpha = 1.0f
    }

    private fun onSentenceGenerated(result: SentenceResult) {
        if (result.text.isBlank()) return
        setSentenceState(true)
        isRenderingWords = true
        try {
            dialogueWords.clear()
            signedWords.clear()
            rawTokens.clear()
            binding.tvMessage.setText(result.text)
            val len = binding.tvMessage.text?.length ?: 0
            binding.tvMessage.setSelection(len)
        } finally {
            isRenderingWords = false
            updateBlinkAnimationState()
            updateUncertainWordsBadge()
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

            // Halt detection meter and apply matching dialogue box rainbow sheen
            binding.progressBuffer.setHaltedRainbowSheen(true)
            binding.tvDetectionLabel.text = "Sentence active"
            binding.tvBufferPercent.text = "100%"
        } else {
            binding.siriBorderView.stopAnimation()
            binding.siriBorderView.visibility = View.GONE
            val density = resources.displayMetrics.density
            binding.cardMessageBox.strokeWidth = (1f * density).toInt()

            // Resume detection meter
            binding.progressBuffer.setHaltedRainbowSheen(false)
            binding.progressBuffer.setProgress(0, false)
            binding.tvDetectionLabel.text = "Detecting"
            binding.tvBufferPercent.text = "0%"
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
        if (isSentenceState) return
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
        binding.tvMessage.setOnTouchListener { v, event ->
            val widget = v as? TextView ?: return@setOnTouchListener false
            val text = widget.text as? Spanned
            if (text != null && widget.layout != null) {
                val action = event.action
                if (action == MotionEvent.ACTION_UP) {
                    val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
                    val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
                    val line = widget.layout.getLineForVertical(y)
                    val off = widget.layout.getOffsetForHorizontal(line, x.toFloat())
                    val spans = text.getSpans(off, off, UncertainWordClickableSpan::class.java)
                    if (spans.isNotEmpty()) {
                        spans[0].onClick(widget)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        binding.badgeUncertainWords.setOnClickListener {
            showUncertainWordsListDialog()
        }

        binding.btnFlipCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            appSettings.isFrontCamera = isFrontCamera
            cameraSessionManager?.isFrontCamera = isFrontCamera
            predictor?.reset()
            landmarker?.reset()
            cameraSessionManager?.bindCamera()
        }

        binding.btnBackspace.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performBackspace()
                    backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                    backspaceCurrentIntervalMs = 220L
                    backspaceHandler.postDelayed(backspaceRepeatRunnable, 380L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                    backspaceCurrentIntervalMs = 220L
                    true
                }
                else -> false
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
                binding.btnMakeSentence.alpha = 1.0f

                if (!hasText) {
                    lastEmittedWord = null
                    dialogueWords.clear()
                    signedWords.clear()
                    rawTokens.clear()
                    sentenceComposer?.reset()
                    if (!isSentenceState) {
                        setSentenceState(false)
                    }
                    updateBlinkAnimationState()
                    updateUncertainWordsBadge()
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
            dialogueWords.clear()
            signedWords.clear()
            rawTokens.clear()
            binding.tvMessage.setText("")
            binding.tvUncertaintyBanner.visibility = View.GONE
            lastEmittedWord = null
            sentenceComposer?.reset()
            setSentenceState(false)
            binding.btnMakeSentence.isEnabled = true
            binding.btnMakeSentence.text = "Turn into sentence"
            binding.btnMakeSentence.alpha = 1.0f
            updateBlinkAnimationState()
            updateUncertainWordsBadge()
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
        if (cameraSessionManager == null) {
            cameraSessionManager = CameraSessionManager(
                context = this,
                lifecycleOwner = this,
                previewView = binding.cameraPreview,
                onFramePrepared = { prepared -> processFrame(prepared) }
            ).apply {
                isFrontCamera = this@MainActivity.isFrontCamera
            }
        }
        cameraSessionManager?.start()
    }

    private fun processFrame(prepared: Bitmap) {
        val lm = landmarker ?: return

        if (appSettings.isFacialAnalysisEnabled) {
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
                                val (isQ, isNeg) = classifier.latestLinguisticMarkers
                                sentenceComposer?.currentMarkers = LinguisticMarkers(isQuestion = isQ, isNegation = isNeg)
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
        } else {
            sentenceComposer?.currentTone = SentenceTone.NEUTRAL
            runOnUiThread { binding.overlayView.setDetectedEmotion(null) }
        }

        lm.detectAsync(prepared, SystemClock.elapsedRealtime())
    }

    private fun showAlternativesPickerForWord(targetWord: DialogueWord) {
        choicesDialog?.dismiss()
        val dialogView = layoutInflater.inflate(R.layout.dialog_sign_choices, null)

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvChoicesSubtitle)
        val altsContainer = dialogView.findViewById<LinearLayout>(R.id.layoutAlternativesContainer)
        val btnKeep = dialogView.findViewById<MaterialButton>(R.id.btnKeepCurrent)

        val confPct = (targetWord.confidence * 100).toInt()
        tvSubtitle.text = "Moderate confidence ($confPct%), tap the blinking red words to adjust"
        btnKeep.text = "Keep '${targetWord.displayWord}'"

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnKeep.setOnClickListener {
            targetWord.tier = ConfidenceTier.HIGH
            renderSignedWords()
            Haptics.confirm(binding.root)
            dialog.dismiss()
        }

        val distinctAlts = targetWord.alternatives.filter {
            !it.label.equals(targetWord.rawLabel, ignoreCase = true)
        }

        if (distinctAlts.isNotEmpty()) {
            for (alt in distinctAlts) {
                val itemView = layoutInflater.inflate(R.layout.item_sign_choice_alternative, altsContainer, false)
                val tvLabel = itemView.findViewById<TextView>(R.id.tvAlternativeLabel)
                val tvConf = itemView.findViewById<TextView>(R.id.tvAlternativeConfidence)

                val altFormatted = alt.label.replace('_', ' ').split(' ').joinToString(" ") {
                    it.replaceFirstChar { c -> c.uppercase() }
                }
                tvLabel.text = altFormatted
                tvConf.text = "${(alt.confidence * 100).toInt()}%"

                itemView.setOnClickListener {
                    replaceDialogueWord(targetWord, alt.label, altFormatted, alt.confidence)
                    dialog.dismiss()
                }
                altsContainer.addView(itemView)
            }
        } else {
            dialogView.findViewById<View>(R.id.tvAlternativesHeader).visibility = View.GONE
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        choicesDialog = dialog
        dialog.show()
    }

    private fun replaceDialogueWord(
        targetWord: DialogueWord,
        newRawLabel: String,
        newDisplayWord: String,
        newConfidence: Float
    ) {
        targetWord.rawLabel = newRawLabel
        targetWord.displayWord = newDisplayWord
        targetWord.tier = ConfidenceTier.HIGH
        targetWord.confidence = newConfidence

        val idx = dialogueWords.indexOfFirst { it.id == targetWord.id }
        if (idx != -1) {
            if (idx < signedWords.size) signedWords[idx] = newDisplayWord
            if (idx < rawTokens.size) rawTokens[idx] = newRawLabel
        }
        sentenceComposer?.setTokens(rawTokens.toList())
        renderSignedWords()
        Haptics.confirm(binding.root)
        Toast.makeText(this, "Updated sign to '$newDisplayWord'", Toast.LENGTH_SHORT).show()
    }

    private fun showUncertainWordsListDialog() {
        val uncertainWords = dialogueWords.filter { it.tier == ConfidenceTier.MODERATE }
        if (uncertainWords.isEmpty()) {
            Toast.makeText(this, "No uncertain words to review", Toast.LENGTH_SHORT).show()
            updateUncertainWordsBadge()
            return
        }

        reviewDialog?.dismiss()
        val dialogView = layoutInflater.inflate(R.layout.dialog_review_words, null)
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.layoutReviewWordsList)
        val btnClose = dialogView.findViewById<View>(R.id.btnDismissReview)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        for (word in uncertainWords) {
            val itemView = layoutInflater.inflate(R.layout.item_review_word, listContainer, false)
            val tvLabel = itemView.findViewById<TextView>(R.id.tvReviewWordLabel)
            val tvConf = itemView.findViewById<TextView>(R.id.tvReviewWordConfidence)

            tvLabel.text = word.displayWord
            tvConf.text = "${(word.confidence * 100).toInt()}% conf"

            itemView.setOnClickListener {
                dialog.dismiss()
                showAlternativesPickerForWord(word)
            }
            listContainer.addView(itemView)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        reviewDialog = dialog
        dialog.show()
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
        startVision()
        updateBlinkAnimationState()
        updateUncertainWordsBadge()
        sentenceComposer?.apply {
            currentLanguage = SentenceLanguage.fromCode(appSettings.sentenceLanguage)
            pauseDurationMs = appSettings.sentencePauseDurationMs
        }
        binding.btnMakeSentence.visibility = if (appSettings.isLlmSynthesisEnabled) View.VISIBLE else View.GONE
        binding.btnMakeSentence.alpha = 1.0f
    }

    override fun onPause() {
        stopVision()
        backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
        choicesDialog?.dismiss()
        reviewDialog?.dismiss()
        blinkAnimator?.cancel()
        super.onPause()
    }

    override fun onStart() { super.onStart(); startVision() }
    override fun onStop() { stopVision(); super.onStop() }
    override fun onDestroy() {
        cameraSessionManager?.release()
        cameraSessionManager = null
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

        backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
        choicesDialog?.dismiss()
        reviewDialog?.dismiss()
        blinkAnimator?.cancel()
        blinkAnimator = null
        stopStatusDotBlink()
        speech?.release()
        speech = null
        super.onDestroy()
    }
}
