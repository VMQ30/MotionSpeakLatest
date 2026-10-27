package com.example.motionspeak

/**
 * Interface for generating sentences from recognized sign language tokens.
 * Implementations include:
 * 1. [DeterministicSentenceSynthesizer] - Offline fallback using rule-based and vocabulary-anchored composition.
 * 2. [LlmSynthesizer] - On-device small language model inference using MediaPipe GenAI.
 */
interface SentenceSynthesizer {

    /**
     * Synthesizes a natural, grammatical sentence from recognized tokens, facial tone, and target language.
     * Guaranteed to never throw unhandled exceptions or crash.
     */
    suspend fun compose(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage
    ): SentenceResult

    suspend fun close() {}
}
