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
            "angry", "disgust", "contempt" -> "negative"
            else -> "neutral"
        }
    }
}
