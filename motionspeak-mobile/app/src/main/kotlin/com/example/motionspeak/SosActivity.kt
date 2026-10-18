package com.example.motionspeak

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fullscreen scarlet SOS emergency alert screen.
 * Forces max audio volume, runs an unlimited audio and haptic vibration loop,
 * and dismisses immediately upon tapping anywhere.
 */
class SosActivity : AppCompatActivity() {

    private var isSosRunning = true
    private var audioManager: AudioManager? = null
    private var originalVolume: Int = -1
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioLoopJob: Job? = null
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and display over lock screen for emergency
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_sos)

        // Force maximum audio volume
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.let { am ->
            originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            } catch (_: Throwable) {}
        }

        // Tap anywhere to stop
        val stopListener = View.OnClickListener { stopSosAndDismiss() }
        findViewById<View>(R.id.sosRoot).setOnClickListener(stopListener)
        findViewById<View>(R.id.sosContent).setOnClickListener(stopListener)
        findViewById<View>(R.id.cardTapToStop).setOnClickListener(stopListener)
        findViewById<View>(R.id.tvTapToStop).setOnClickListener(stopListener)

        // Intercept back button to also cleanly stop and dismiss
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopSosAndDismiss()
            }
        })

        // Pulse warning icon
        val warningIcon = findViewById<View>(R.id.ivSosWarning)
        if (warningIcon != null) {
            pulseAnimator = ObjectAnimator.ofFloat(warningIcon, View.ALPHA, 1.0f, 0.35f, 1.0f).apply {
                duration = 750L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        // Start unlimited haptic vibration loop
        startVibrationLoop()

        // Initialize tone generator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Throwable) {}

        // Initialize TextToSpeech and start unlimited audio loop
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS && isSosRunning) {
                tts?.let { TtsVoiceHelper.applyFilipinoLocale(it) }
                ttsReady = true
            }
        }

        startAudioLoop()
    }

    private fun startVibrationLoop() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // SOS Morse code waveform: 3 short, 3 long, 3 short, followed by pause
        val sosWaveform = longArrayOf(
            0,
            160, 100, 160, 100, 160, 250,
            420, 100, 420, 100, 420, 250,
            160, 100, 160, 100, 160, 600
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Repeat from index 0 endlessly
                vibrator?.vibrate(VibrationEffect.createWaveform(sosWaveform, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(sosWaveform, 0)
            }
        } catch (_: Throwable) {}
    }

    private fun startAudioLoop() {
        audioLoopJob = lifecycleScope.launch {
            val phrases = listOf(
                "Saklolo! Saklolo! Tulungan niyo ako!",
                "Emergency! Emergency! Help me!",
                "Saklolo! Humihingi ng tulong!"
            )
            var phraseIndex = 0

            while (isSosRunning && isActive) {
                // Play tone beep
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800)
                } catch (_: Throwable) {}

                // Speak emergency alert phrase
                if (ttsReady && tts != null) {
                    val phrase = phrases[phraseIndex % phrases.size]
                    phraseIndex++
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    try {
                        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "sos_alert_${System.currentTimeMillis()}")
                    } catch (_: Throwable) {}
                }

                // Wait interval before repeating
                delay(2600L)
            }
        }
    }

    private fun stopSosAndDismiss() {
        if (!isSosRunning) return
        isSosRunning = false

        // Cancel pulse animator
        try {
            pulseAnimator?.cancel()
            pulseAnimator = null
        } catch (_: Throwable) {}

        // Cancel coroutine audio loop
        try {
            audioLoopJob?.cancel()
            audioLoopJob = null
        } catch (_: Throwable) {}

        // Stop and release ToneGenerator
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Throwable) {}

        // Stop and shutdown TextToSpeech
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Throwable) {}

        // Stop haptic vibration immediately
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (_: Throwable) {}

        // Restore original audio volume
        if (originalVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            } catch (_: Throwable) {}
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.tab_fade_out)
    }

    override fun onPause() {
        super.onPause()
        // If user navigates away or switches apps, ensure emergency audio and vibration don't leak
        if (isSosRunning) {
            stopSosAndDismiss()
        }
    }

    override fun onDestroy() {
        stopSosAndDismiss()
        super.onDestroy()
    }
}
