package com.example.motionspeak

object ToneMapper {
    /**
     * Maps the 8 HSEmotion emotions into the 4 Tone categories for the LLM.
     */
    fun fromEmotion(emotion: String): String {
        return when (emotion.lowercase()) {
            "neutral" -> "neutral"
            "happy" -> "positive"
            "sad", "fear", "surprise" -> "concerned"
            "angry", "disgust", "contempt" -> "negative"
            else -> "neutral"
        }
    }
}