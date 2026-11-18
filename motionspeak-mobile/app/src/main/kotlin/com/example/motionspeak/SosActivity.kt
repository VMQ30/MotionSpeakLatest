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
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Fullscreen scarlet SOS emergency alert screen.
 * Forces max audio volume, runs audio and haptic vibration loops, and provides
 * one-tap access to preconfigured emergency communication messages.
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

    private lateinit var tvActiveEmergencyMsg: TextView
    private lateinit var btnSosMedical: MaterialButton
    private lateinit var btnSosPolice: MaterialButton
    private lateinit var btnSosFire: MaterialButton
    private lateinit var btnSosCannotSpeak: MaterialButton
    private lateinit var cardTapToStop: View

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

        tvActiveEmergencyMsg = findViewById(R.id.tvActiveEmergencyMsg)
        btnSosMedical = findViewById(R.id.btnSosMedical)
        btnSosPolice = findViewById(R.id.btnSosPolice)
        btnSosFire = findViewById(R.id.btnSosFire)
        btnSosCannotSpeak = findViewById(R.id.btnSosCannotSpeak)
        cardTapToStop = findViewById(R.id.cardTapToStop)

        cardTapToStop.setOnClickListener { stopSosAndDismiss() }

        btnSosMedical.setOnClickListener {
            playEmergencyPhrase("Kailangan ko ng ambulansya at tulong medikal agad!", "🚑 MEDICAL EMERGENCY\nKailangan ng ambulansya!")
        }

        btnSosPolice.setOnClickListener {
            playEmergencyPhrase("Kailangan ko ng agarang tulong ng pulis!", "👮 POLICE EMERGENCY\nKailangan ng pulis!")
        }

        btnSosFire.setOnClickListener {
            playEmergencyPhrase("May sunog! Pakiusap tumawag ng bumbero!", "🔥 FIRE EMERGENCY\nMay sunog! Tumawag ng bumbero!")
        }

        btnSosCannotSpeak.setOnClickListener {
            playEmergencyPhrase("Hindi po ako makapagsalita. Pakiusap tulungan niyo po ako.", "⚠️ ASSISTANCE NEEDED\nHindi makapagsalita, humihingi ng tulong.")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopSosAndDismiss()
            }
        })

        initHapticsAndAudio()
        startAudioLoop()
        startVisualPulse()
    }

    private fun playEmergencyPhrase(speech: String, displayText: String) {
        tvActiveEmergencyMsg.visibility = View.VISIBLE
        tvActiveEmergencyMsg.text = displayText

        // Temporarily pause siren tone to let TTS speak clearly
        toneGenerator?.stopTone()
        if (ttsReady && tts != null) {
            tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "sos_phrase")
        }
    }

    private fun initHapticsAndAudio() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Throwable) {}

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("fil", "PH")
                ttsReady = true
            }
        }
    }

    private fun startAudioLoop() {
        audioLoopJob = lifecycleScope.launch {
            val timings = longArrayOf(0, 300, 150, 300, 150, 600, 200, 600, 200, 600, 150, 300, 150, 300)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255)

            while (isActive && isSosRunning) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200)
                } catch (_: Throwable) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(timings, -1)
                }

                delay(1600)
            }
        }
    }

    private fun startVisualPulse() {
        val content = findViewById<View>(R.id.sosContent) ?: return
        pulseAnimator = ObjectAnimator.ofFloat(content, "alpha", 1f, 0.45f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopSosAndDismiss() {
        if (!isSosRunning) return
        isSosRunning = false

        audioLoopJob?.cancel()
        audioLoopJob = null

        pulseAnimator?.cancel()
        pulseAnimator = null

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Throwable) {}

        try {
            vibrator?.cancel()
        } catch (_: Throwable) {}

        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Throwable) {}

        if (originalVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            } catch (_: Throwable) {}
        }

        finish()
    }

    override fun onDestroy() {
        stopSosAndDismiss()
        super.onDestroy()
    }
}
