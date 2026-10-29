package com.example.motionspeak

import java.util.Locale

/**
 * Builds deterministic, structured prompt templates for on-device Gemma LLM inference.
 * Strictly adheres to vocabulary constraints, meaning preservation, zero word loss,
 * negation rules, and language targets (English or Filipino).
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
            append("You are an intelligent, offline sentence generator for Filipino Sign Language (FSL) translation.\n")
            append("Task: Convert ALL recognized gesture words into one natural, grammatically correct, coherent sentence (or two short connected sentences) in $languageName.\n")
            append("Context tone: $toneName.\n\n")
            append("Strict Rules:\n")
            append("1. ZERO WORD LOSS: Every single word listed in the Input MUST be included in the sentence. Never drop, omit, skip, or summarize away any signed word (e.g. if input contains 4 words, all 4 concepts must appear in the output).\n")
            append("2. COHERENCE: Connect all signed words smoothly into a sensible sentence using natural conjunctions (e.g. 'and', 'also', 'because', 'so', 'please' in English; 'at', 'pati', 'dahil', 'kaya', 'pakiusap' in Filipino).\n")
            append("3. STRUCTURE: Put greetings first, followed by bodily states and symptoms, then medical needs, actions, or inquiries.\n")
            append("4. STRICT NEGATION: When 'no' precedes a symptom or condition (e.g. 'no, fever'), clearly express that the user does not have it (e.g. 'I have no fever'). Preserve negation strictly (\"no\", \"i don't understand\").\n")
            append("5. MEDICAL ACCURACY: Use ONLY the meanings supported by the input labels. Never invent symptoms, medications, or details not signed.\n")
            append("6. OUTPUT FORMAT: Return ONLY the final sentence. No quotes, no markdown, no commentary, no explanations, no prefix.\n\n")

            // Few-shot guidance based on language
            if (language == SentenceLanguage.ENGLISH) {
                append("Examples:\n")
                append("Input: hello, how are you\n")
                append("Sentence: Hello, how are you?\n\n")
                append("Input: hurt, broken bone\n")
                append("Sentence: I am hurt and have a broken bone.\n\n")
                append("Input: headache, fever, please, medicines\n")
                append("Sentence: I have a headache and a fever. Please give me medicine.\n\n")
                append("Input: good morning, sick, consult, thank you\n")
                append("Sentence: Good morning, I am feeling sick and want to consult a doctor. Thank you.\n\n")
                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Sentence: There has been an accident causing a broken bone. Please call an ambulance, I need to be admitted to the hospital.\n\n")
                append("Input: no, allergies\n")
                append("Sentence: I have no allergies.\n\n")
                append("Input: no, fever, cough\n")
                append("Sentence: I have no fever, but I have a cough.\n\n")
                append("Input: pregnant, giving birth, ambulance\n")
                append("Sentence: I am pregnant and giving birth, please call an ambulance!\n\n")
                append("Input: asthma, breathing, emergency\n")
                append("Sentence: I have asthma and difficulty breathing. This is an emergency!\n\n")
                append("Input: accident, ambulance, emergency\n")
                append("Sentence: There has been an accident. This is an emergency, please call an ambulance!\n\n")
                append("Input: dizziness, xray, laboratory, please\n")
                append("Sentence: I feel dizzy. Please arrange an X-ray and laboratory tests.\n\n")
            } else {
                append("Halimbawa:\n")
                append("Input: hello, how are you\n")
                append("Sentence: Kamusta, kamusta ka?\n\n")
                append("Input: hurt, broken bone\n")
                append("Sentence: Nasasaktan ako at may bali sa buto.\n\n")
                append("Input: headache, fever, please, medicines\n")
                append("Sentence: May sakit ako sa ulo at lagnat. Pakiusap, bigyan ako ng gamot.\n\n")
                append("Input: good morning, sick, consult, thank you\n")
                append("Sentence: Magandang umaga, masama ang aking pakiramdam at nais kong kumonsulta sa doktor. Salamat.\n\n")
                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Sentence: May aksidente at may bali sa buto. Pakiusap tumawag ng ambulansya, kailangan kong maospital.\n\n")
                append("Input: no, allergies\n")
                append("Sentence: Wala akong alerhiya.\n\n")
                append("Input: no, fever, cough\n")
                append("Sentence: Wala akong lagnat, ngunit mayroon akong ubo.\n\n")
                append("Input: pregnant, giving birth, ambulance\n")
                append("Sentence: Buntis ako at manganganak na, pakiusap tumawag ng ambulansya!\n\n")
                append("Input: asthma, breathing, emergency\n")
                append("Sentence: May hika ako at nahihirapang huminga. Emerhensya ito!\n\n")
                append("Input: accident, ambulance, emergency\n")
                append("Sentence: May naganap na aksidente. Tumawag ng ambulansya, ito ay emerhensya.\n\n")
                append("Input: dizziness, xray, laboratory, please\n")
                append("Sentence: Nahihilo ako. Pakiusap, kailangan ko ng X-ray at pagsusuri sa laboratoryo.\n\n")
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
