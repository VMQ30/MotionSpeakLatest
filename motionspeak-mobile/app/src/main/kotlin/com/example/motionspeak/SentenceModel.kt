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

    val isUrgent: Boolean get() = this == CONCERNED
    val isPositive: Boolean get() = this == POSITIVE
    val isNegative: Boolean get() = this == NEGATIVE
    val isNeutral: Boolean get() = this == NEUTRAL

    companion object {
        fun fromToneString(tone: String?): SentenceTone =
            values().firstOrNull { it.id.equals(tone, ignoreCase = true) } ?: NEUTRAL
    }
}

/**
 * Non-manual grammatical markers identified from facial geometry.
 *
 * Brow classification rules (each side normalized by face height):
 *  - [isYesNoQuestion]: Only one brow raised (> 0.085), the other at or below threshold.
 *    Signals a yes/no question; sentence ends with `?`.
 *  - [isExclamation]: Both brows raised (> 0.085).
 *    Signals emphasis/exclamation; sentence ends with `!`.
 *  - [isNegation]: Both brows furrowed (< 0.045).
 *    Signals negation/denial; sentence is negated.
 *
 * [isYesNoQuestion] and [isExclamation] are mutually exclusive by definition.
 * Legacy [isQuestion] is kept as a computed alias of [isYesNoQuestion] so that
 * existing call-sites that have not been migrated yet continue to compile.
 */
data class LinguisticMarkers(
    val isYesNoQuestion: Boolean = false,
    val isExclamation: Boolean = false,
    val isNegation: Boolean = false
) {
    /** Backward-compat alias: a single-brow raise is treated as a yes/no question. */
    val isQuestion: Boolean get() = isYesNoQuestion

    val hasActiveMarkers: Boolean get() = isYesNoQuestion || isExclamation || isNegation
}

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
    val linguisticMarkers: LinguisticMarkers = LinguisticMarkers(),
    val t0CaptureMs: Long = 0L,
    val targetPrompt: SentencePrompt? = null
)
