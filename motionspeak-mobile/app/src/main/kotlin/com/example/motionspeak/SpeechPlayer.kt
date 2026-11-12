package com.example.motionspeak

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

// One serial lane for every voice change and utterance, so a speak() can never
// overtake the voice change queued just before it. Daemon: it must not hold the
// process open.
private val speechLane = Executors.newSingleThreadExecutor { r ->
    Thread(r, "motionspeak-tts").apply { isDaemon = true }
}.asCoroutineDispatcher()

/**
 * The app's text-to-speech: Filipino, the user's preferred voice and volume.
 * MainActivity, WordDetailActivity, and SettingsActivity each use this player.
 *
 * The voice is applied before every utterance. After the first call this is
 * TtsVoiceHelper's cached fast path, and it means a voice changed in Settings
 * takes effect on the next utterance on either screen.
 */
class SpeechPlayer(
    context: Context,
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    @Volatile var latestTtsLatencyMs: Double = 0.0
        private set

    val isReady: Boolean
        get() = ready

    data class UtteranceContext(
        val ttsSubmitNs: Long,
        val t0CaptureMs: Long,
        val onAudioStart: ((e2eLatencyMs: Long, ttsDurationMs: Double) -> Unit)? = null
    )

    private val utteranceContexts = java.util.concurrent.ConcurrentHashMap<String, UtteranceContext>()

    init {
        // applicationContext: release() shuts the engine down off the main thread,
        // so the unbind can land after the Activity is gone. Bound through the
        // Activity, that would leak its ServiceConnection.
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    TtsVoiceHelper.applyFilipinoLocale(engine)
                    engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId != null) {
                                val ctx = utteranceContexts.remove(utteranceId)
                                val nowMs = android.os.SystemClock.elapsedRealtime()
                                val durationMs = if (ctx != null) (android.os.SystemClock.elapsedRealtimeNanos() - ctx.ttsSubmitNs) / 1e6 else 0.0
                                latestTtsLatencyMs = durationMs

                                val e2eMs = if (ctx != null && ctx.t0CaptureMs > 0L) {
                                    (nowMs - ctx.t0CaptureMs).coerceAtLeast(1L)
                                } else {
                                    durationMs.toLong().coerceAtLeast(1L)
                                }

                                ctx?.onAudioStart?.invoke(e2eMs, durationMs)
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null) utteranceContexts.remove(utteranceId)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null) {
                                val ctx = utteranceContexts.remove(utteranceId)
                                if (ctx != null && ctx.t0CaptureMs > 0L) {
                                    val e2eMs = (android.os.SystemClock.elapsedRealtime() - ctx.t0CaptureMs).coerceAtLeast(1L)
                                    ctx.onAudioStart?.invoke(e2eMs, 0.0)
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            onError(utteranceId)
                        }
                    })
                }
                ready = true
                pending?.let { speak(it) }
                pending = null
            }
        }
    }

    /**
     * Speaks [text]. Before the engine is ready it is dropped, or held and spoken
     * once ready when [queueUntilReady] (only the latest held text survives).
     * [t0CaptureMs] carries the initial camera frame capture timestamp to calculate true e2e latency on audio onset.
     */
    fun speak(
        text: String,
        queueUntilReady: Boolean = false,
        t0CaptureMs: Long = 0L,
        onAudioStart: ((e2eLatencyMs: Long, ttsDurationMs: Double) -> Unit)? = null
    ) {
        if (!ready) {
            if (queueUntilReady) pending = text
            if (t0CaptureMs > 0L && onAudioStart != null) {
                val e2eMs = (android.os.SystemClock.elapsedRealtime() - t0CaptureMs).coerceAtLeast(1L)
                onAudioStart.invoke(e2eMs, 0.0)
            }
            return
        }
        val engine = tts ?: return
        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                (appSettings.volume / 100f).coerceIn(0f, 1f),
            )
        }
        val utteranceId = "ms_tts_${android.os.SystemClock.elapsedRealtimeNanos()}"
        utteranceContexts[utteranceId] = UtteranceContext(
            ttsSubmitNs = android.os.SystemClock.elapsedRealtimeNanos(),
            t0CaptureMs = t0CaptureMs,
            onAudioStart = onAudioStart
        )

        // Resolving the voice can block on a query into the TTS engine process
        // (cached after the first time), so it must not run on the main thread.
        scope.launch(speechLane) {
            TtsVoiceHelper.applyPreferredVoice(engine, appSettings)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    /**
     * Pre-applies the preferred voice immediately in the background so
     * changing the voice takes effect with zero latency on the next utterance.
     */
    fun applyVoice() {
        val engine = tts ?: return
        scope.launch(speechLane) {
            TtsVoiceHelper.applyPreferredVoice(engine, appSettings)
        }
    }

    /**
     * shutdown() blocks until the engine connection started in the constructor
     * has finished binding, so leaving right after entering stalled the main
     * thread for 0.6-1.25 s. Shut down on teardownExecutor instead.
     */
    fun release() {
        val doomed = tts
        tts = null
        ready = false
        pending = null
        if (doomed != null) teardownExecutor.execute { doomed.shutdown() }
    }
}
