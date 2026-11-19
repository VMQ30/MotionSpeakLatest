package com.example.motionspeak

object ToneMapper {
    /**
     * Maps the 8 HSEmotion emotions into the 4 Tone categories for the LLM.
     * Enforces a minimum confidence threshold to prevent ambiguous facial
     * expressions from altering sentence semantics.
     */
    fun fromEmotion(emotion: String, confidence: Float = 1.0f): String {
        if (confidence < 0.50f) return "neutral"
        return when (emotion.lowercase()) {
            "neutral" -> "neutral"
            "happy" -> "positive"
            "sad", "fear", "surprise" -> "concerned"
            // angry/disgust/contempt in a medical context signals urgency and distress — treat as concerned
            "angry", "disgust", "contempt" -> "concerned"
            else -> "neutral"
        }
    }

    /**
     * Standardized emotional state mapping for clinical triage and test evaluation:
     * - sad -> Sad
     * - fear -> Fear
     * - angry -> Angry
     * - disgust -> Disgust
     * - contempt -> Contempt
     * - happy -> Happy
     * - surprise -> Surprise
     * - neutral -> Neutral
     */
    fun toTriageAffect(emotion: String?): String {
        return when (emotion?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "happy" -> "Happy"
            "sad" -> "Sad"
            "surprise" -> "Surprise"
            "fear" -> "Fear"
            "angry" -> "Angry"
            "disgust" -> "Disgust"
            "contempt" -> "Contempt"
            "neutral" -> "Neutral"
            else -> "Neutral"
        }
    }
}
