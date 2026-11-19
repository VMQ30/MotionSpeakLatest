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
    ): String = buildPrompt(tokens, tone, language, LinguisticMarkers())

    /**
     * Builds the complete prompt for [tokens], [tone], [language], and non-manual [markers].
     */
    fun buildPrompt(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage,
        markers: LinguisticMarkers
    ): String {
        val normalizedLabels = tokens.map { it.trim().replace('_', ' ') }
        val inputString = normalizedLabels.joinToString(", ")
        val toneName = tone.id.lowercase(Locale.ROOT)
        val languageName = language.displayName

        return buildString {
            append("<start_of_turn>user\n")
            append("You are an intelligent, offline sentence generator for Filipino Sign Language (FSL) translation.\n")
            append("Task: Convert ALL recognized gesture words into one natural, grammatically correct, coherent sentence (or two short connected sentences) in $languageName.\n")
            append("Context tone: $toneName.\n")
            if (markers.isQuestion) {
                append("Facial Non-Manual Marker: Question / Inquiry (Raised eyebrows detected).\n")
            }
            if (markers.isNegation) {
                append("Facial Non-Manual Marker: Negation / Denial (Furrowed eyebrows detected).\n")
            }
            append("\nStrict Rules:\n")
            append("1. ZERO WORD LOSS: Every single word listed in the Input MUST be included in the sentence. Never drop, omit, skip, or summarize away any signed word (e.g. if input contains 4 words, all 4 concepts must appear in the output).\n")
            append("2. COHERENCE: Connect all signed words smoothly into a sensible sentence using natural conjunctions (e.g. 'and', 'also', 'because', 'so', 'please' in English; 'at', 'pati', 'dahil', 'kaya', 'pakiusap' in Filipino).\n")
            append("3. DYNAMIC SENTENCE STRUCTURE (NO FIXED TEMPLATES):\n")
            append("   - Do NOT utilize rigid or identical sentence templates across different tones.\n")
            append("   - Dynamically reorder clauses, shift grammatical emphasis, and restructure syntax based on the emotional context:\n")
            append("     * 'concerned': Urgently restructure the sentence. Prioritize immediate calls for aid and front critical conditions (e.g. 'Please help, there has been an accident! I am hurt and have a broken bone!').\n")
            append("     * 'positive': Restructure into warm, polite, and reassuring phrasing (e.g. 'Good morning! I appreciate your help and want to consult a doctor. Thank you so much!').\n")
            append("     * 'negative': Restructure to highlight physical discomfort, struggle, or direct refusal.\n")
            append("     * 'neutral': Use standard, calm, objective declarative structure.\n")
            append("4. STRICT NEGATION: When 'no' precedes a symptom or condition (e.g. 'no, fever'), clearly express that the user does not have it (e.g. 'I have no fever'). Preserve negation strictly (\"no\", \"i don't understand\").\n")
            append("5. MEDICAL ACCURACY: Use ONLY the meanings supported by the input labels. Never invent symptoms, medications, or details not signed (no dramatic melodrama like 'killing me with agony').\n")
            append("6. OUTPUT FORMAT: Return ONLY the final sentence. No quotes, no markdown, no commentary, no explanations, no prefix.\n")
            append("7. DEDUPLICATION: When input words are duplicated or repeated (e.g. 'hello, hello' or 'water, water, please'), merge the duplicated words as one single concept. Never repeat words back-to-back in the sentence.\n")
            append("8. EMOTION & TONE SYNERGY:\n")
            append("   - Sentence Restructuring: The model must dynamically generate diverse sentence structures suited to the emotional tone rather than repeating a static sentence template.\n")
            append("   - If Context tone is 'concerned', reflect distress and urgency in active phrasing and structure, using exclamation marks (!) for urgent medical requests or emergencies.\n")
            append("   - If Context tone is 'positive', use warm, polite structure and friendly punctuation.\n")
            append("   - If Context tone is 'negative', emphasize discomfort or refusal.\n")
            append("   - If Context tone is 'neutral', use calm declarative structure with periods.\n")
            append("9. FACIAL GRAMMATICAL MARKERS & PUNCTUATION:\n")
            append("   - When Question Marker is detected (raised eyebrows) or the gesture is an inquiry, structure the sentence as a question and terminate with a question mark (?).\n")
            append("   - When Negation Marker is detected (furrowed eyebrows), ensure the sentence reflects negation, denial, or refusal.\n\n")

            // Few-shot guidance based on language
            if (language == SentenceLanguage.ENGLISH) {
                append("Examples:\n")
                append("Input: hello, hello\n")
                append("Context tone: positive\n")
                append("Sentence: Hello!\n\n")

                append("Input: water, water, please\n")
                append("Context tone: neutral\n")
                append("Sentence: Water, please.\n\n")

                append("Input: hello, how are you\n")
                append("Context tone: neutral\n")
                append("Sentence: Hello, how are you?\n\n")

                append("Input: hurt, broken bone\n")
                append("Context tone: neutral\n")
                append("Sentence: I am hurt and have a broken bone.\n\n")

                append("Input: hurt, broken bone\n")
                append("Context tone: concerned\n")
                append("Sentence: Please help, I am hurt and have a broken bone!\n\n")

                append("Input: headache, fever, please, medicines\n")
                append("Context tone: neutral\n")
                append("Sentence: I have a headache and a fever. Please give me medicine.\n\n")

                append("Input: headache, fever, please, medicines\n")
                append("Context tone: concerned\n")
                append("Sentence: I am suffering from a headache and fever, please give me medicine right away!\n\n")

                append("Input: good morning, sick, consult, thank you\n")
                append("Context tone: neutral\n")
                append("Sentence: Good morning, I am feeling sick and want to consult a doctor. Thank you.\n\n")

                append("Input: good morning, sick, consult, thank you\n")
                append("Context tone: positive\n")
                append("Sentence: Good morning! I am feeling sick and would appreciate consulting a doctor. Thank you so much!\n\n")

                append("Input: accident, hurt, broken bone\n")
                append("Context tone: neutral\n")
                append("Sentence: There has been an accident. I am hurt and have a broken bone.\n\n")

                append("Input: accident, hurt, broken bone\n")
                append("Context tone: concerned\n")
                append("Sentence: Please help, there has been an accident! I am hurt and have a broken bone!\n\n")

                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Context tone: neutral\n")
                append("Sentence: There has been an accident causing a broken bone. Please call an ambulance, I need to be admitted to the hospital.\n\n")

                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Context tone: concerned\n")
                append("Sentence: There has been an accident with a broken bone! Call an ambulance immediately, I must be hospitalized!\n\n")

                append("Input: no, allergies\n")
                append("Context tone: neutral\n")
                append("Sentence: I have no allergies.\n\n")

                append("Input: no, fever, cough\n")
                append("Context tone: neutral\n")
                append("Sentence: I have no fever, but I have a cough.\n\n")

                append("Input: no, fever, cough\n")
                append("Context tone: negative\n")
                append("Sentence: I do not have a fever, but I am troubled by a bad cough.\n\n")

                append("Input: pregnant, giving birth, ambulance\n")
                append("Context tone: concerned\n")
                append("Sentence: I am pregnant and giving birth, please call an ambulance immediately!\n\n")

                append("Input: asthma, breathing, emergency\n")
                append("Context tone: concerned\n")
                append("Sentence: This is an emergency! I have asthma and I cannot breathe properly!\n\n")

                append("Input: accident, ambulance, emergency\n")
                append("Context tone: neutral\n")
                append("Sentence: There has been an accident. This is an emergency, please call an ambulance.\n\n")

                append("Input: accident, ambulance, emergency\n")
                append("Context tone: concerned\n")
                append("Sentence: There has been an accident! This is an emergency, please call an ambulance right away!\n\n")

                append("Input: dizziness, xray, laboratory, please\n")
                append("Context tone: neutral\n")
                append("Sentence: I feel dizzy. Please arrange an X-ray and laboratory tests.\n\n")
            } else {
                append("Halimbawa:\n")
                append("Input: hello, hello\n")
                append("Context tone: positive\n")
                append("Sentence: Kamusta!\n\n")

                append("Input: water, water, please\n")
                append("Context tone: neutral\n")
                append("Sentence: Tubig, pakiusap.\n\n")

                append("Input: hello, how are you\n")
                append("Context tone: neutral\n")
                append("Sentence: Kamusta, kamusta ka?\n\n")

                append("Input: hurt, broken bone\n")
                append("Context tone: neutral\n")
                append("Sentence: Nasasaktan ako at may bali sa buto.\n\n")

                append("Input: hurt, broken bone\n")
                append("Context tone: concerned\n")
                append("Sentence: Tulungan ninyo ako, nasasaktan ako at may bali sa buto!\n\n")

                append("Input: headache, fever, please, medicines\n")
                append("Context tone: neutral\n")
                append("Sentence: May sakit ako sa ulo at lagnat. Pakiusap, bigyan ako ng gamot.\n\n")

                append("Input: headache, fever, please, medicines\n")
                append("Context tone: concerned\n")
                append("Sentence: Masakit na masakit ang aking ulo at may lagnat ako, pakiusap bigyan agad ako ng gamot!\n\n")

                append("Input: good morning, sick, consult, thank you\n")
                append("Context tone: neutral\n")
                append("Sentence: Magandang umaga, masama ang aking pakiramdam at nais kong kumonsulta sa doktor. Salamat.\n\n")

                append("Input: good morning, sick, consult, thank you\n")
                append("Context tone: positive\n")
                append("Sentence: Magandang umaga po! May sakit po ako at nais ko pong kumonsulta sa doktor. Maraming salamat po!\n\n")

                append("Input: accident, hurt, broken bone\n")
                append("Context tone: neutral\n")
                append("Sentence: May aksidente. Nasasaktan ako at may bali sa buto.\n\n")

                append("Input: accident, hurt, broken bone\n")
                append("Context tone: concerned\n")
                append("Sentence: Saklolo, may aksidente! Nasasaktan ako at may bali sa buto!\n\n")

                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Context tone: neutral\n")
                append("Sentence: May aksidente at may bali sa buto. Pakiusap tumawag ng ambulansya, kailangan kong maospital.\n\n")

                append("Input: accident, broken bone, ambulance, hospitalized\n")
                append("Context tone: concerned\n")
                append("Sentence: May naganap na aksidente at may bali sa buto! Tumawag agad ng ambulansya, kailangang maospital kaagad!\n\n")

                append("Input: no, allergies\n")
                append("Context tone: neutral\n")
                append("Sentence: Wala akong alerhiya.\n\n")

                append("Input: no, fever, cough\n")
                append("Context tone: neutral\n")
                append("Sentence: Wala akong lagnat, ngunit mayroon akong ubo.\n\n")

                append("Input: no, fever, cough\n")
                append("Context tone: negative\n")
                append("Sentence: Hindi ako nilalagnat, subalit labis akong pinahihirapan ng aking ubo.\n\n")

                append("Input: pregnant, giving birth, ambulance\n")
                append("Context tone: concerned\n")
                append("Sentence: Buntis ako at manganganak na ngayon din, pakiusap tumawag agad ng ambulansya!\n\n")

                append("Input: asthma, breathing, emergency\n")
                append("Context tone: concerned\n")
                append("Sentence: Emerhensya ito! May hika ako at nahihirapang huminga!\n\n")

                append("Input: accident, ambulance, emergency\n")
                append("Context tone: neutral\n")
                append("Sentence: May naganap na aksidente. Ito ay emerhensya, pakiusap tumawag ng ambulansya.\n\n")

                append("Input: accident, ambulance, emergency\n")
                append("Context tone: concerned\n")
                append("Sentence: May naganap na aksidente! Emerhensya ito, tumawag agad ng ambulansya!\n\n")

                append("Input: dizziness, xray, laboratory, please\n")
                append("Context tone: neutral\n")
                append("Sentence: Nahihilo ako. Pakiusap, kailangan ko ng X-ray at pagsusuri sa laboratoryo.\n\n")
            }

            append("Input: $inputString\n")
            append("Context tone: $toneName\n")
            if (markers.isQuestion) {
                append("Facial Non-Manual Marker: Question / Inquiry (Raised eyebrows detected).\n")
            }
            if (markers.isNegation) {
                append("Facial Non-Manual Marker: Negation / Denial (Furrowed eyebrows detected).\n")
            }
            append("Sentence:<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**
     * Sanitizes raw output from the LLM to strip conversational boilerplate,
     * markdown formatting, quotes, and hallucinations.
     */
    fun sanitizeOutput(raw: String): String = sanitizeOutput(raw, LinguisticMarkers(), SentenceTone.NEUTRAL)

    /**
     * Sanitizes raw output from the LLM, shaping terminal punctuation to reflect
     * non-manual [markers] and [tone].
     */
    fun sanitizeOutput(
        raw: String,
        markers: LinguisticMarkers = LinguisticMarkers(),
        tone: SentenceTone = SentenceTone.NEUTRAL
    ): String {
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

        // Merge any accidentally duplicated consecutive words
        cleaned = cleaned.replace(Regex("""\\b(\\w+)\\s+\\1\\b""", RegexOption.IGNORE_CASE), "$1")

        // Ensure proper ending punctuation shaped by linguistic markers and tone synergy
        if (cleaned.isNotEmpty()) {
            if (markers.isQuestion) {
                if (!cleaned.endsWith("?")) {
                    cleaned = if (cleaned.endsWith(".") || cleaned.endsWith("!")) {
                        cleaned.dropLast(1) + "?"
                    } else {
                        "$cleaned?"
                    }
                }
            } else if (tone == SentenceTone.CONCERNED && isEmergencyOrUrgent(cleaned)) {
                if (!cleaned.endsWith("!")) {
                    cleaned = if (cleaned.endsWith(".")) {
                        cleaned.dropLast(1) + "!"
                    } else if (!cleaned.endsWith("?")) {
                        "$cleaned!"
                    } else cleaned
                }
            } else if (tone == SentenceTone.NEUTRAL && cleaned.endsWith("!")) {
                cleaned = cleaned.dropLast(1) + "."
            } else if (!cleaned.endsWith(".") && !cleaned.endsWith("?") && !cleaned.endsWith("!")) {
                cleaned = "$cleaned."
            }
        }

        return cleaned
    }

    private fun isEmergencyOrUrgent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val urgentKeywords = listOf(
            "emergency", "accident", "ambulance", "heart attack", "call 911", "hospital",
            "emerhensya", "aksidente", "ambulansya", "saklolo", "tulong", "atake sa puso", "ospital",
            "hurt", "sick", "pain", "bleeding", "breathing", "dizzy", "nasasaktan", "may sakit", "nahihilo", "huminga"
        )
        return urgentKeywords.any { lower.contains(it) }
    }
}
