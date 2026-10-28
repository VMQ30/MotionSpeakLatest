package com.example.motionspeak

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM sentence synthesizer utilizing MediaPipe GenAI (LlmInference).
 * Executes fully offline with zero network calls.
 *
 * Enforces zero-word-loss verification via [WordTokenMatcher] so that no user gesture
 * labels are ever dropped or omitted during sentence construction. If any tokens
 * are missed by the LLM or if inference encounters an error, it gracefully recovers
 * or delegates to [fallbackSynthesizer] (DeterministicSentenceSynthesizer).
 */
class LlmSynthesizer(
    private val context: Context,
    private val modelFile: File,
    private val fallbackSynthesizer: SentenceSynthesizer = DeterministicSentenceSynthesizer()
) : SentenceSynthesizer {

    private companion object {
        private const val TAG = "LlmSynthesizer"
        private const val MAX_TOKENS = 256
    }

    private var llmInference: LlmInference? = null

    /**
     * True when the LLM interpreter has successfully initialized and is ready for inference.
     */
    var isReady: Boolean = false
        private set

    /**
     * Initializes the LlmInference runtime on Dispatchers.IO.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Cannot initialize LLM: model file not found at ${modelFile.absolutePath}")
            isReady = false
            return@withContext false
        }

        try {
            Log.i(TAG, "Initializing MediaPipe LlmInference with ${modelFile.name} (${modelFile.length()} bytes)...")
            val startTime = SystemClock.elapsedRealtime()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isReady = true

            val loadDuration = SystemClock.elapsedRealtime() - startTime
            Log.i(TAG, "LlmInference initialized successfully in ${loadDuration}ms")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize LlmInference: ${e.message}", e)
            llmInference = null
            isReady = false
            false
        }
    }

    override suspend fun compose(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage
    ): SentenceResult = withContext(Dispatchers.Default) {
        if (tokens.isEmpty()) {
            return@withContext fallbackSynthesizer.compose(tokens, tone, language)
        }

        val inference = llmInference
        if (!isReady || inference == null) {
            Log.w(TAG, "LLM not ready; delegating to deterministic fallback.")
            return@withContext fallbackSynthesizer.compose(tokens, tone, language)
        }

        val prompt = LlmPromptBuilder.buildPrompt(tokens, tone, language)
        val startTime = SystemClock.elapsedRealtime()

        try {
            val rawOutput = inference.generateResponse(prompt)
            val duration = SystemClock.elapsedRealtime() - startTime
            val sanitized = LlmPromptBuilder.sanitizeOutput(rawOutput)

            if (sanitized.isBlank()) {
                Log.w(TAG, "LLM returned blank output in ${duration}ms; falling back.")
                return@withContext fallbackSynthesizer.compose(tokens, tone, language)
            }

            // Verify that NO signed tokens were dropped by the LLM
            val missingTokens = WordTokenMatcher.getMissingTokens(tokens, sanitized)

            if (missingTokens.isEmpty()) {
                Log.i(TAG, "LLM generated sentence with complete 100% token coverage in ${duration}ms: \"$sanitized\"")
                return@withContext SentenceResult(
                    text = sanitized,
                    originalTokens = tokens,
                    language = language,
                    tone = tone,
                    isLlmGenerated = true,
                    latencyMs = duration
                )
            }

            Log.w(TAG, "LLM omitted tokens: $missingTokens. Performing zero-word-loss recovery.")

            // If the LLM captured the bulk of the sentence, weave missing tokens into the output;
            // otherwise use fallbackSynthesizer to guarantee 100% token coverage with natural syntax.
            val finalSentence = if (sanitized.length >= 10 && missingTokens.size <= 2 && missingTokens.size < tokens.size) {
                val recovered = fallbackSynthesizer.compose(missingTokens, tone, language).text
                if (recovered.isNotBlank()) {
                    val cleanBase = sanitized.trimEnd('.', '!', '?')
                    "$cleanBase. $recovered"
                } else {
                    fallbackSynthesizer.compose(tokens, tone, language).text
                }
            } else {
                fallbackSynthesizer.compose(tokens, tone, language).text
            }

            Log.i(TAG, "Synthesized sentence with 100% token preservation in ${duration}ms: \"$finalSentence\"")
            SentenceResult(
                text = finalSentence,
                originalTokens = tokens,
                language = language,
                tone = tone,
                isLlmGenerated = true,
                latencyMs = duration
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Inference error: ${e.message}; falling back to deterministic synthesizer", e)
            fallbackSynthesizer.compose(tokens, tone, language)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            try {
                llmInference?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LlmInference: ${e.message}")
            } finally {
                llmInference = null
                isReady = false
                fallbackSynthesizer.close()
            }
        }
    }
}
