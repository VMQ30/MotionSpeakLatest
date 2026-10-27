package com.example.motionspeak

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SentenceComposer coordinates the buffering of recognized FSL tokens,
 * detects end-of-thought pauses (default 2 seconds), and triggers sentence synthesis.
 *
 * Thread-safety: Token ingestion and pause scheduling can be called from any thread.
 * Synthesis is executed on [dispatcher] without blocking the main/camera threads.
 */
class SentenceComposer(
    private var synthesizer: SentenceSynthesizer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    var pauseDurationMs: Long = DEFAULT_PAUSE_MS,
    var dedupeWindowMs: Long = DEFAULT_DEDUPE_MS,
    var maxBufferSize: Int = DEFAULT_MAX_BUFFER_SIZE,
    var autoComposeEnabled: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_PAUSE_MS = 2000L
        const val DEFAULT_DEDUPE_MS = 1000L
        const val DEFAULT_MAX_BUFFER_SIZE = 10
    }

    private val mutex = Mutex()
    private val currentBuffer = mutableListOf<String>()
    private val pendingBuffer = mutableListOf<String>()

    private var lastEmittedLabel: String? = null
    private var lastEmittedTime: Long = 0L

    private var pauseTimerJob: Job? = null
    private var synthesisJob: Job? = null
    private var isSynthesizing = false

    // Current state inputs
    @Volatile var currentTone: SentenceTone = SentenceTone.NEUTRAL
    @Volatile var currentLanguage: SentenceLanguage = SentenceLanguage.ENGLISH

    // Listeners
    var onSentenceComposed: ((SentenceResult) -> Unit)? = null
    var onTokenAdded: ((token: String, bufferSnapshot: List<String>) -> Unit)? = null
    var onComposingStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Swap or update synthesizer (e.g., when LLM becomes available or is disabled).
     */
    fun updateSynthesizer(newSynthesizer: SentenceSynthesizer) {
        synthesizer = newSynthesizer
    }

    /**
     * Ingest a recognized gesture label.
     * Applies duplicate suppression.
     * If [autoComposeEnabled] is true, restarts the pause detection timer.
     */
    fun addToken(label: String) {
        val now = clock()
        if (label == lastEmittedLabel && (now - lastEmittedTime) < dedupeWindowMs) {
            // Rapid duplicate suppressed
            return
        }
        lastEmittedLabel = label
        lastEmittedTime = now

        scope.launch(dispatcher) {
            mutex.withLock {
                if (isSynthesizing) {
                    // Collect into pending buffer so words arriving mid-synthesis are not lost
                    if (pendingBuffer.size >= maxBufferSize) {
                        pendingBuffer.removeAt(0)
                    }
                    pendingBuffer.add(label)
                } else {
                    if (currentBuffer.size >= maxBufferSize) {
                        currentBuffer.removeAt(0)
                    }
                    currentBuffer.add(label)
                }
                val snapshot = if (isSynthesizing) pendingBuffer.toList() else currentBuffer.toList()
                onTokenAdded?.invoke(label, snapshot)

                // Only schedule pause timer if autoComposeEnabled is true
                if (autoComposeEnabled) {
                    restartPauseTimerLocked()
                }
            }
        }
    }

    /**
     * Must be called while holding [mutex].
     */
    private fun restartPauseTimerLocked() {
        pauseTimerJob?.cancel()
        pauseTimerJob = scope.launch(dispatcher) {
            delay(pauseDurationMs)
            if (isActive) {
                triggerComposition()
            }
        }
    }

    /**
     * Explicitly trigger composition (e.g. from UI button) or called on pause timeout.
     * Optionally takes [fallbackTokens] if buffers are empty.
     */
    fun triggerComposition(fallbackTokens: List<String>? = null) {
        scope.launch(dispatcher) {
            val tokensToCompose: List<String>
            val toneSnapshot = currentTone
            val langSnapshot = currentLanguage

            mutex.withLock {
                pauseTimerJob?.cancel()
                pauseTimerJob = null

                if (currentBuffer.isEmpty()) {
                    if (pendingBuffer.isNotEmpty()) {
                        currentBuffer.addAll(pendingBuffer)
                        pendingBuffer.clear()
                    } else if (!fallbackTokens.isNullOrEmpty()) {
                        currentBuffer.addAll(fallbackTokens)
                    } else {
                        return@launch
                    }
                }

                tokensToCompose = currentBuffer.toList()
                currentBuffer.clear()
                isSynthesizing = true
            }

            onComposingStateChanged?.invoke(true)

            try {
                val result = synthesizer.compose(tokensToCompose, toneSnapshot, langSnapshot)
                onSentenceComposed?.invoke(result)
            } catch (e: Exception) {
                // If anything unexpected occurs, fall back to safe pass-through text
                val fallbackText = tokensToCompose.joinToString(" ")
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() } + "."
                val fallbackResult = SentenceResult(
                    text = fallbackText,
                    originalTokens = tokensToCompose,
                    language = langSnapshot,
                    tone = toneSnapshot,
                    isLlmGenerated = false,
                    latencyMs = 0L
                )
                onSentenceComposed?.invoke(fallbackResult)
            } finally {
                mutex.withLock {
                    isSynthesizing = false
                    // If new tokens arrived during synthesis, handle them
                    if (pendingBuffer.isNotEmpty()) {
                        currentBuffer.addAll(pendingBuffer)
                        pendingBuffer.clear()
                        if (autoComposeEnabled) {
                            restartPauseTimerLocked()
                        }
                    }
                }
                onComposingStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Clears all token buffers and cancels pending timers.
     */
    fun reset() {
        scope.launch(dispatcher) {
            mutex.withLock {
                pauseTimerJob?.cancel()
                pauseTimerJob = null
                currentBuffer.clear()
                pendingBuffer.clear()
                lastEmittedLabel = null
                lastEmittedTime = 0L
            }
        }
    }

    /**
     * Release resources.
     */
    fun close() {
        pauseTimerJob?.cancel()
        synthesisJob?.cancel()
        scope.cancel()
    }
}
