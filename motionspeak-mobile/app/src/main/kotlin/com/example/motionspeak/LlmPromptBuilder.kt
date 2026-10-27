package com.example.motionspeak

import java.util.Locale

/**
 * Builds deterministic, structured prompt templates for on-device Gemma LLM inference.
 * Strictly adheres to the vocabulary constraints, meaning preservation, negation rules,
 * and language targets (English or Filipino).
 */
object LlmPromptBuilder {

    /**
     * Builds the complete prompt for [tokens], [tone], and [language].
     */
    fun buildPrompt(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage
    ): String {
        val normalizedLabels = tokens.map { it.trim().replace('_', ' ') }
        val inputString = normalizedLabels.joinToString(", ")
        val toneName = tone.id.lowercase(Locale.ROOT)
        val languageName = language.displayName

        return buildString {
            append("<start_of_turn>user\n")
            append("You are an offline sentence generator for Filipino Sign Language (FSL) translation.\n")
            append("Task: Convert the recognized gesture labels into one natural, grammatically correct sentence in $languageName.\n")
            append("Context tone: $toneName.\n\n")
            append("Strict Rules:\n")
            append("1. Use ONLY the meanings supported by the input labels. Never invent symptoms, names, medications, causes, or locations.\n")
            append("2. Preserve every concept signed by the user. Never drop, remove, or substitute the main words signed (e.g. if input is \"hurt, broken bone\", include BOTH concepts: \"I am hurt and have a broken bone\", do NOT replace \"broken bone\" with something else).\n")
            append("3. Preserve negation strictly (\"no\", \"i don't understand\").\n")
            append("4. Treat phrase labels (e.g. \"good morning\", \"how are you\", \"i dont understand\") as complete concepts.\n")
            append("5. Keep the sentence concise (1 short sentence, or 2 short sentences at most).\n")
            append("6. Return ONLY the sentence. No quotes, no markdown, no commentary, no explanations.\n\n")

            // Few-shot guidance based on language
            if (language == SentenceLanguage.ENGLISH) {
                append("Examples:\n")
                append("Input: hello, how are you\n")
                append("Sentence: Hello, how are you?\n\n")
                append("Input: hurt, broken bone\n")
                append("Sentence: I am hurt and have a broken bone.\n\n")
                append("Input: headache, fever, please, medicines\n")
                append("Sentence: I have a headache and a fever. Please give me medicine.\n\n")
                append("Input: no, allergies\n")
                append("Sentence: I have no allergies.\n\n")
                append("Input: accident, ambulance, emergency\n")
                append("Sentence: There has been an accident. Please call an ambulance, this is an emergency.\n\n")
            } else {
                append("Halimbawa:\n")
                append("Input: hello, how are you\n")
                append("Sentence: Kamusta, kamusta ka?\n\n")
                append("Input: hurt, broken bone\n")
                append("Sentence: Nasasaktan ako at may bali sa buto.\n\n")
                append("Input: headache, fever, please, medicines\n")
                append("Sentence: May sakit ako sa ulo at lagnat. Pakiusap, bigyan ako ng gamot.\n\n")
                append("Input: no, allergies\n")
                append("Sentence: Wala akong alerhiya.\n\n")
                append("Input: accident, ambulance, emergency\n")
                append("Sentence: May naganap na aksidente. Tumawag ng ambulansya, ito ay emerhensya.\n\n")
            }

            append("Input: $inputString\n")
            append("Sentence:<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**
     * Sanitizes raw output from the LLM to strip conversational boilerplate,
     * markdown formatting, quotes, and hallucinations.
     */
    fun sanitizeOutput(raw: String): String {
        var cleaned = raw.trim()

        // Remove markdown code fences if present
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            cleaned = cleaned.removeSurrounding("```").trim()
            if (cleaned.startsWith("text\n") || cleaned.startsWith("markdown\n")) {
                cleaned = cleaned.substringAfter('\n').trim()
            }
        }

        // Remove prefix like "Sentence:" or "Output:"
        val prefixes = listOf("sentence:", "output:", "translation:", "pangungusap:", "sagot:")
        for (prefix in prefixes) {
            if (cleaned.lowercase(Locale.ROOT).startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length).trim()
            }
        }

        // Remove wrapping quotes
        cleaned = cleaned.removeSurrounding("\"").removeSurrounding("'").trim()

        // Truncate to first paragraph/sentence if model kept generating
        val newlineIdx = cleaned.indexOf('\n')
        if (newlineIdx != -1) {
            cleaned = cleaned.substring(0, newlineIdx).trim()
        }

        // Ensure ending punctuation
        if (cleaned.isNotEmpty() && !cleaned.endsWith(".") && !cleaned.endsWith("?") && !cleaned.endsWith("!")) {
            cleaned = "$cleaned."
        }

        return cleaned
    }
}
