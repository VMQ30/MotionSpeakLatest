package com.example.motionspeak

/**
 * Target language for sentence generation.
 * Managed independently from the UI display language setting.
 */
enum class SentenceLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    FILIPINO("fil", "Filipino");

    companion object {
        fun fromCode(code: String?): SentenceLanguage =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

/**
 * 4 high-level tones derived from HSEmotion facial expression classification.
 */
enum class SentenceTone(val id: String) {
    NEUTRAL("neutral"),
    POSITIVE("positive"),
    CONCERNED("concerned"),
    NEGATIVE("negative");

    companion object {
        fun fromToneString(tone: String?): SentenceTone =
            values().firstOrNull { it.id.equals(tone, ignoreCase = true) } ?: NEUTRAL
    }
}

/**
 * Non-manual grammatical markers identified from facial geometry.
 */
data class LinguisticMarkers(
    val isQuestion: Boolean = false,
    val isNegation: Boolean = false
)

/**
 * Output of sentence synthesis.
 */
data class SentenceResult(
    val text: String,
    val originalTokens: List<String>,
    val language: SentenceLanguage,
    val tone: SentenceTone,
    val isLlmGenerated: Boolean,
    val latencyMs: Long = 0L,
    val linguisticMarkers: LinguisticMarkers = LinguisticMarkers()
)
