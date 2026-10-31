package com.example.motionspeak

import java.util.Locale

/**
 * Deterministic sentence synthesizer.
 * Serves as the primary offline fallback when the LLM is absent, disabled, or fails.
 * Produces structured, punctuated, and grammatically consistent sentences in English and Filipino
 * based strictly on the recognized labels and known word bank definitions.
 *
 * Guarantees ZERO WORD LOSS: Every single input gesture label is preserved and woven
 * into a coherent, natural thought.
 */
class DeterministicSentenceSynthesizer(
    private val wordBank: List<WordBankWord> = defaultWordBank()
) : SentenceSynthesizer {

    // Fast lookup for label -> Filipino translation
    private val filipinoMap: Map<String, String> = buildMap {
        for (w in wordBank) {
            val key = normalizeLabelKey(w.label)
            w.filipino_translation?.trim()?.takeIf { it.isNotEmpty() }?.let { trans ->
                put(key, trans)
            }
        }
    }

    override suspend fun compose(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage
    ): SentenceResult {
        if (tokens.isEmpty()) {
            return SentenceResult(
                text = "",
                originalTokens = emptyList(),
                language = language,
                tone = tone,
                isLlmGenerated = false,
                latencyMs = 0L
            )
        }

        val deduplicatedTokens = tokens.filterIndexed { index, token ->
            index == 0 || !token.trim().equals(tokens[index - 1].trim(), ignoreCase = true)
        }

        val text = when (language) {
            SentenceLanguage.ENGLISH -> composeEnglish(deduplicatedTokens, tone)
            SentenceLanguage.FILIPINO -> composeFilipino(deduplicatedTokens, tone)
        }

        return SentenceResult(
            text = text,
            originalTokens = tokens,
            language = language,
            tone = tone,
            isLlmGenerated = false,
            latencyMs = 0L
        )
    }

    override suspend fun compose(
        tokens: List<String>,
        tone: SentenceTone,
        language: SentenceLanguage,
        markers: LinguisticMarkers
    ): SentenceResult {
        val base = compose(tokens, tone, language)
        var adjustedText = base.text
        if (markers.isQuestion && !adjustedText.endsWith("?")) {
            adjustedText = if (adjustedText.endsWith(".")) {
                adjustedText.dropLast(1) + "?"
            } else {
                "${base.text}?"
            }
        }
        return base.copy(text = adjustedText, linguisticMarkers = markers)
    }

    private fun composeEnglish(tokens: List<String>, tone: SentenceTone): String {
        val normalized = tokens.map { normalizeEnglishToken(it) }

        // Special handling for single question / greeting / negation labels
        if (normalized.size == 1) {
            val single = normalized.first()
            return formatSingleConceptEnglish(single, tone)
        }

        // Handle 2-token negation patterns like ["no", "allergies"] -> "I have no allergies."
        if (normalized.first().equals("no", ignoreCase = true) && normalized.size == 2) {
            val second = normalized[1].lowercase(Locale.ENGLISH)
            return if (isSymptomWordEnglish(second)) {
                "I have no $second."
            } else {
                "I do not have $second."
            }
        }

        val rawTokens = normalized.map { it.lowercase(Locale.ENGLISH).trim().replace('_', ' ') }
        val consumed = BooleanArray(rawTokens.size)
        val sentences = mutableListOf<String>()

        fun isFree(idx: Int) = !consumed[idx]
        fun use(idx: Int) { consumed[idx] = true }

        // A. Greetings & Questions
        val greetings = rawTokens.indices.filter { isFree(it) && isGreetingPhrase(rawTokens[it]) }
        val questions = rawTokens.indices.filter { isFree(it) && isQuestionPhrase(rawTokens[it]) }

        for (g in greetings) {
            use(g)
            sentences.add(ensurePunctuation(rawTokens[g].replaceFirstChar { it.uppercase() }, "."))
        }
        for (q in questions) {
            use(q)
            sentences.add(ensurePunctuation(rawTokens[q].replaceFirstChar { it.uppercase() }, "?"))
        }

        // B. Negations with following symptoms: e.g. ["no", "fever", "cough"]
        val noIndices = rawTokens.indices.filter { isFree(it) && rawTokens[it] == "no" }
        for (n in noIndices) {
            val nextSymptom = rawTokens.indices.firstOrNull { it > n && isFree(it) && isSymptomWordEnglish(rawTokens[it]) }
            if (nextSymptom != null) {
                use(n)
                use(nextSymptom)
                val negWord = rawTokens[nextSymptom]
                val otherSymptoms = rawTokens.indices.filter { isFree(it) && isSymptomWordEnglish(rawTokens[it]) }
                if (otherSymptoms.isNotEmpty()) {
                    val otherWords = otherSymptoms.map { rawTokens[it] }
                    otherSymptoms.forEach { use(it) }
                    sentences.add("I have no $negWord, but I have ${formatSymptomListEnglish(otherWords)}.")
                } else {
                    sentences.add("I have no $negWord.")
                }
            }
        }

        // C. Emergencies
        val heartAttackIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "heart attack" }
        val accidentIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "accident" }
        val emergencyIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "emergency" }
        val ambulanceIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "ambulance" }

        if (heartAttackIdx != null) {
            use(heartAttackIdx)
            if (emergencyIdx != null) use(emergencyIdx)
            if (ambulanceIdx != null) use(ambulanceIdx)
            sentences.add("Someone is having a heart attack. This is an emergency, please call an ambulance!")
        } else if (accidentIdx != null) {
            use(accidentIdx)
            if (emergencyIdx != null && ambulanceIdx != null) {
                use(emergencyIdx)
                use(ambulanceIdx)
                sentences.add("There has been an accident. This is an emergency, please call an ambulance!")
            } else if (ambulanceIdx != null) {
                use(ambulanceIdx)
                sentences.add("There has been an accident, please call an ambulance!")
            } else if (emergencyIdx != null) {
                use(emergencyIdx)
                sentences.add("There has been an accident. This is an emergency!")
            } else {
                sentences.add("There has been an accident!")
            }
        } else if (emergencyIdx != null && ambulanceIdx != null) {
            use(emergencyIdx)
            use(ambulanceIdx)
            sentences.add("This is an emergency, please call an ambulance!")
        } else if (emergencyIdx != null) {
            use(emergencyIdx)
            sentences.add("This is an emergency!")
        }

        // D. States & Symptoms
        val stateIndices = rawTokens.indices.filter { isFree(it) && isStateWordEnglish(rawTokens[it]) }
        val symptomIndices = rawTokens.indices.filter { isFree(it) && isSymptomWordEnglish(rawTokens[it]) }
        val consultIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "consult" }
        val hospitalIdx = rawTokens.indices.firstOrNull { isFree(it) && (rawTokens[it] == "hospital" || rawTokens[it] == "hospitalized") }
        val pleaseIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "please" }

        val states = stateIndices.map { rawTokens[it] }
        val symptoms = symptomIndices.map { rawTokens[it] }

        stateIndices.forEach { use(it) }
        symptomIndices.forEach { use(it) }

        if (states.isNotEmpty() && symptoms.isNotEmpty()) {
            val statePart = formatStateEnglish(states)
            val symptomPart = formatSymptomListEnglish(symptoms)
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("$statePart and have $symptomPart, and want to consult a doctor at the hospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("$statePart and have $symptomPart, and want to consult a doctor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("$statePart and have $symptomPart, and need to be hospitalized.")
            } else {
                sentences.add("$statePart and have $symptomPart.")
            }
        } else if (states.isNotEmpty()) {
            val statePart = formatStateEnglish(states)
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("$statePart and want to consult a doctor at the hospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("$statePart and want to consult a doctor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("$statePart and need to be hospitalized.")
            } else {
                sentences.add("$statePart.")
            }
        } else if (symptoms.isNotEmpty()) {
            val symptomPart = formatSymptomListEnglish(symptoms)
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("I have $symptomPart and want to consult a doctor at the hospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("I have $symptomPart and want to consult a doctor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("I have $symptomPart and need to be admitted to the hospital.")
            } else {
                sentences.add("I have $symptomPart.")
            }
        }

        // E. Breathing difficulty
        val breathingIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it] == "breathing" }
        if (breathingIdx != null) {
            use(breathingIdx)
            sentences.add("I am having difficulty breathing.")
        }

        // F. Standalone Consult & Hospital
        if (consultIdx != null && isFree(consultIdx)) {
            use(consultIdx)
            val hasPls = pleaseIdx != null && isFree(pleaseIdx)
            if (pleaseIdx != null && isFree(pleaseIdx)) {
                use(pleaseIdx)
            }
            if (hospitalIdx != null && isFree(hospitalIdx)) {
                use(hospitalIdx)
                if (hasPls) sentences.add("Please, I want to consult a doctor at the hospital.")
                else sentences.add("I want to consult a doctor at the hospital.")
            } else {
                if (hasPls) sentences.add("Please, I want to consult.")
                else sentences.add("I want to consult a doctor.")
            }
        } else if (hospitalIdx != null && isFree(hospitalIdx)) {
            use(hospitalIdx)
            sentences.add("I need to be admitted to the hospital.")
        }

        // G. Medical Needs & Requests
        val needIndices = rawTokens.indices.filter { isFree(it) && isNeedWordEnglish(rawTokens[it]) }
        if (needIndices.isNotEmpty()) {
            needIndices.forEach { use(it) }
            val needs = needIndices.map { rawTokens[it] }
            val needPart = formatNeedListEnglish(needs)
            if (pleaseIdx != null && isFree(pleaseIdx)) {
                use(pleaseIdx)
                sentences.add("Please give me $needPart.")
            } else if (symptoms.isNotEmpty()) {
                sentences.add("Please give me $needPart.")
            } else {
                sentences.add("I need $needPart.")
            }
        }

        // H. Ambulance (if still free)
        if (ambulanceIdx != null && isFree(ambulanceIdx)) {
            use(ambulanceIdx)
            sentences.add("Please call an ambulance!")
        }

        // I. Conversational feedback & polite tokens
        for (i in rawTokens.indices) {
            if (!isFree(i)) continue
            when (rawTokens[i]) {
                "thank you" -> {
                    use(i)
                    sentences.add("Thank you.")
                }
                "i don't understand" -> {
                    use(i)
                    sentences.add("I do not understand.")
                }
                "understand" -> {
                    use(i)
                    sentences.add("I understand.")
                }
                "please" -> {
                    use(i)
                    sentences.add("Please.")
                }
                "yes" -> {
                    use(i)
                    sentences.add("Yes.")
                }
                "no" -> {
                    use(i)
                    sentences.add("No.")
                }
                "maybe" -> {
                    use(i)
                    sentences.add("Maybe.")
                }
            }
        }

        // J. ZERO WORD LOSS GUARANTEE: Collect any remaining unconsumed tokens
        val remaining = rawTokens.indices.filter { isFree(it) }
        if (remaining.isNotEmpty()) {
            val remainingWords = remaining.map { normalized[it] }
            remaining.forEach { use(it) }
            val clause = when (remainingWords.size) {
                1 -> ensurePunctuation(remainingWords[0].replaceFirstChar { it.uppercase() }, ".")
                2 -> "${remainingWords[0].replaceFirstChar { it.uppercase() }} and ${remainingWords[1]}."
                else -> "${remainingWords.dropLast(1).joinToString(", ").replaceFirstChar { it.uppercase() }}, and ${remainingWords.last()}."
            }
            sentences.add(clause)
        }

        return sentences.joinToString(" ")
    }

    private fun composeFilipino(tokens: List<String>, tone: SentenceTone): String {
        val normalized = tokens.map { translateTokenToFilipino(it) }

        if (normalized.size == 1) {
            val single = normalized.first()
            return formatSingleConceptFilipino(single, tone)
        }

        // Negation pattern: ["Hindi", "Alerhiya"] -> "Wala akong alerhiya."
        if (normalized.first().equals("Hindi", ignoreCase = true) && normalized.size == 2) {
            return "Wala akong ${normalized[1].lowercase(Locale.ROOT)}."
        }

        val rawTokens = normalized.map { it.lowercase(Locale.ROOT).trim().replace('_', ' ') }
        val consumed = BooleanArray(rawTokens.size)
        val sentences = mutableListOf<String>()

        fun isFree(idx: Int) = !consumed[idx]
        fun use(idx: Int) { consumed[idx] = true }

        // A. Greetings & Questions
        val greetings = rawTokens.indices.filter { isFree(it) && isGreetingPhraseFilipino(rawTokens[it]) }
        val questions = rawTokens.indices.filter { isFree(it) && isQuestionPhraseFilipino(rawTokens[it]) }

        for (g in greetings) {
            use(g)
            sentences.add(ensurePunctuation(normalized[g].replaceFirstChar { it.uppercase() }, "."))
        }
        for (q in questions) {
            use(q)
            sentences.add(ensurePunctuation(normalized[q].replaceFirstChar { it.uppercase() }, "?"))
        }

        // B. Negations with following symptoms: e.g. ["hindi", "lagnat", "ubo"]
        val noIndices = rawTokens.indices.filter { isFree(it) && (rawTokens[it] == "hindi" || rawTokens[it] == "wala") }
        for (n in noIndices) {
            val nextSymptom = rawTokens.indices.firstOrNull { it > n && isFree(it) && isSymptomWordFilipino(rawTokens[it]) }
            if (nextSymptom != null) {
                use(n)
                use(nextSymptom)
                val negWord = rawTokens[nextSymptom]
                val otherSymptoms = rawTokens.indices.filter { isFree(it) && isSymptomWordFilipino(rawTokens[it]) }
                if (otherSymptoms.isNotEmpty()) {
                    val otherWords = otherSymptoms.map { rawTokens[it] }
                    otherSymptoms.forEach { use(it) }
                    sentences.add("Wala akong $negWord, ngunit mayroon akong ${formatSymptomListFilipino(otherWords)}.")
                } else {
                    sentences.add("Wala akong $negWord.")
                }
            }
        }

        // C. Emergencies
        val heartAttackIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("atake") }
        val accidentIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("aksidente") }
        val emergencyIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("emerhensya") }
        val ambulanceIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("ambulansya") }

        if (heartAttackIdx != null) {
            use(heartAttackIdx)
            if (emergencyIdx != null) use(emergencyIdx)
            if (ambulanceIdx != null) use(ambulanceIdx)
            sentences.add("May inaatake sa puso, tumawag agad ng ambulansya!")
        } else if (accidentIdx != null) {
            use(accidentIdx)
            if (emergencyIdx != null && ambulanceIdx != null) {
                use(emergencyIdx)
                use(ambulanceIdx)
                sentences.add("May aksidente. Emerhensya ito, tumawag ng ambulansya!")
            } else if (ambulanceIdx != null) {
                use(ambulanceIdx)
                sentences.add("May aksidente, pakiusap tumawag ng ambulansya!")
            } else if (emergencyIdx != null) {
                use(emergencyIdx)
                sentences.add("May aksidente. Emerhensya ito!")
            } else {
                sentences.add("May aksidente!")
            }
        } else if (emergencyIdx != null && ambulanceIdx != null) {
            use(emergencyIdx)
            use(ambulanceIdx)
            sentences.add("Emerhensya ito, tumawag ng ambulansya!")
        } else if (emergencyIdx != null) {
            use(emergencyIdx)
            sentences.add("Emerhensya ito!")
        }

        // D. States & Symptoms
        val stateIndices = rawTokens.indices.filter { isFree(it) && isStateWordFilipino(rawTokens[it]) }
        val symptomIndices = rawTokens.indices.filter { isFree(it) && isSymptomWordFilipino(rawTokens[it]) }
        val consultIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("konsulta") }
        val hospitalIdx = rawTokens.indices.firstOrNull { isFree(it) && (rawTokens[it].contains("ospital") || rawTokens[it].contains("naospital")) }
        val pleaseIdx = rawTokens.indices.firstOrNull { isFree(it) && (rawTokens[it].contains("pakiusap") || rawTokens[it].contains("paki")) }

        val states = stateIndices.map { rawTokens[it] }
        val symptoms = symptomIndices.map { rawTokens[it] }

        stateIndices.forEach { use(it) }
        symptomIndices.forEach { use(it) }

        if (states.isNotEmpty() && symptoms.isNotEmpty()) {
            val statePart = formatStateFilipino(states).replaceFirstChar { it.uppercase() }
            val symptomPart = formatSymptomListFilipino(symptoms)
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("$statePart at may $symptomPart, at nais kong kumonsulta sa doktor sa ospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("$statePart at may $symptomPart, at nais kong kumonsulta sa doktor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("$statePart at may $symptomPart, at kailangang maospital.")
            } else {
                sentences.add("$statePart at may $symptomPart.")
            }
        } else if (states.isNotEmpty()) {
            val statePart = formatStateFilipino(states).replaceFirstChar { it.uppercase() }
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("$statePart at nais kong kumonsulta sa doktor sa ospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("$statePart at nais kong kumonsulta sa doktor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("$statePart at kailangang maospital.")
            } else {
                sentences.add("$statePart.")
            }
        } else if (symptoms.isNotEmpty()) {
            val symptomPart = formatSymptomListFilipino(symptoms)
            if (consultIdx != null && hospitalIdx != null) {
                use(consultIdx); use(hospitalIdx)
                sentences.add("May $symptomPart ako at nais kong kumonsulta sa doktor sa ospital.")
            } else if (consultIdx != null) {
                use(consultIdx)
                sentences.add("May $symptomPart ako at nais kong kumonsulta sa doktor.")
            } else if (hospitalIdx != null) {
                use(hospitalIdx)
                sentences.add("May $symptomPart ako at kailangang maospital.")
            } else {
                sentences.add("Mayroon akong $symptomPart.")
            }
        }

        // E. Breathing difficulty
        val breathingIdx = rawTokens.indices.firstOrNull { isFree(it) && rawTokens[it].contains("paghinga") }
        if (breathingIdx != null) {
            use(breathingIdx)
            sentences.add("Nahihirapan akong huminga.")
        }

        // F. Standalone Consult & Hospital
        if (consultIdx != null && isFree(consultIdx)) {
            use(consultIdx)
            val hasPls = pleaseIdx != null && isFree(pleaseIdx)
            if (pleaseIdx != null && isFree(pleaseIdx)) {
                use(pleaseIdx)
            }
            if (hospitalIdx != null && isFree(hospitalIdx)) {
                use(hospitalIdx)
                if (hasPls) sentences.add("Pakiusap, nais kong kumonsulta sa doktor sa ospital.")
                else sentences.add("Nais kong kumonsulta sa doktor sa ospital.")
            } else {
                if (hasPls) sentences.add("Pakiusap, nais kong kumonsulta.")
                else sentences.add("Nais kong kumonsulta sa doktor.")
            }
        } else if (hospitalIdx != null && isFree(hospitalIdx)) {
            use(hospitalIdx)
            sentences.add("Kailangan kong maospital.")
        }

        // G. Medical Needs & Requests
        val needIndices = rawTokens.indices.filter { isFree(it) && isNeedWordFilipino(rawTokens[it]) }
        if (needIndices.isNotEmpty()) {
            needIndices.forEach { use(it) }
            val needs = needIndices.map { rawTokens[it] }
            val needPart = formatNeedListFilipino(needs)
            if (pleaseIdx != null && isFree(pleaseIdx)) {
                use(pleaseIdx)
                sentences.add("Pakiusap bigyan ako ng $needPart.")
            } else if (symptoms.isNotEmpty()) {
                sentences.add("Pakiusap bigyan ako ng $needPart.")
            } else {
                sentences.add("Kailangan ko ng $needPart.")
            }
        }

        // H. Ambulance (if still free)
        if (ambulanceIdx != null && isFree(ambulanceIdx)) {
            use(ambulanceIdx)
            sentences.add("Pakiusap tumawag ng ambulansya!")
        }

        // I. Conversational feedback & polite tokens
        for (i in rawTokens.indices) {
            if (!isFree(i)) continue
            when {
                rawTokens[i].contains("salamat") -> {
                    use(i)
                    sentences.add("Salamat.")
                }
                rawTokens[i].contains("hindi ko maintindihan") -> {
                    use(i)
                    sentences.add("Hindi ko maintindihan.")
                }
                rawTokens[i].contains("naiintindihan") -> {
                    use(i)
                    sentences.add("Naiintindihan ko.")
                }
                rawTokens[i].contains("pakiusap") -> {
                    use(i)
                    sentences.add("Pakiusap.")
                }
                rawTokens[i] == "oo" -> {
                    use(i)
                    sentences.add("Oo.")
                }
                rawTokens[i] == "hindi" -> {
                    use(i)
                    sentences.add("Hindi.")
                }
                rawTokens[i].contains("baka") || rawTokens[i].contains("marahil") -> {
                    use(i)
                    sentences.add("Baka.")
                }
            }
        }

        // J. ZERO WORD LOSS GUARANTEE: Collect any remaining unconsumed tokens
        val remaining = rawTokens.indices.filter { isFree(it) }
        if (remaining.isNotEmpty()) {
            val remainingWords = remaining.map { normalized[it] }
            remaining.forEach { use(it) }
            val clause = when (remainingWords.size) {
                1 -> ensurePunctuation(remainingWords[0].replaceFirstChar { it.uppercase() }, ".")
                2 -> "${remainingWords[0].replaceFirstChar { it.uppercase() }} at ${remainingWords[1]}."
                else -> "${remainingWords.dropLast(1).joinToString(", ").replaceFirstChar { it.uppercase() }}, at ${remainingWords.last()}."
            }
            sentences.add(clause)
        }

        return sentences.joinToString(" ")
    }

    private fun normalizeEnglishToken(raw: String): String {
        return when (raw.trim().lowercase(Locale.ENGLISH)) {
            "good_morning" -> "Good morning"
            "good_evening" -> "Good evening"
            "good afternoon" -> "Good afternoon"
            "how_are_you" -> "How are you"
            "how_can_i_help_you" -> "How can I help you"
            "i_dont_understand" -> "I don't understand"
            "thank_you" -> "Thank you"
            "covid_19" -> "COVID-19"
            "broken_bone" -> "broken bone"
            "giving_birth" -> "giving birth"
            "heart_attack" -> "heart attack"
            "sore_throat" -> "sore throat"
            "dry_mouth" -> "dry mouth"
            "blurred_vision" -> "blurred vision"
            "neck_pain" -> "neck pain"
            else -> raw.trim().replace('_', ' ')
        }
    }

    private fun translateTokenToFilipino(raw: String): String {
        val key = normalizeLabelKey(raw)
        return filipinoMap[key] ?: when (key) {
            "no" -> "Hindi"
            "yes" -> "Oo"
            "maybe" -> "Baka"
            "please" -> "Pakiusap"
            "thank you" -> "Salamat"
            "i dont understand", "i don't understand" -> "Hindi ko maintindihan"
            "understand" -> "Naiintindihan"
            "hello", "hi" -> "Kamusta"
            "how are you" -> "Kamusta ka"
            "how can i help you" -> "Paano kita matutulungan"
            else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatSingleConceptEnglish(token: String, tone: SentenceTone): String {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return when {
            isQuestionPhrase(lower) -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "?")
            isGreetingPhrase(lower) -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, ".")
            lower == "i don't understand" -> "I do not understand."
            lower == "thank you" -> "Thank you."
            lower == "understand" -> "I understand."
            lower == "yes" -> "Yes."
            lower == "no" -> "No."
            lower == "maybe" -> "Maybe."
            lower == "please" -> "Please."
            lower == "sick" -> "I am sick."
            lower == "hurt" -> "I am hurt."
            lower == "pregnant" -> "I am pregnant."
            lower == "giving birth" -> "I am giving birth."
            lower == "dizziness" -> "I feel dizzy."
            lower == "consult" -> "I want to consult a doctor."
            lower == "medicines" || lower == "medicine" -> "I need medicine."
            lower == "prescription" -> "I need my prescription."
            lower == "xray" || lower == "x-ray" -> "I need an X-ray."
            lower == "laboratory" -> "I need laboratory tests."
            lower == "injection" -> "I need an injection."
            lower == "iv" -> "I need an IV."
            lower == "surgery" -> "I need surgery."
            lower == "thermometer" -> "I need a thermometer."
            lower == "hospitalized" -> "I need to be admitted to the hospital."
            lower == "compression" -> "I need compression."
            lower == "circumcision" -> "I need a circumcision consultation."
            lower == "abortion" -> "I need an abortion consultation."
            lower == "accident" -> "There has been an accident!"
            lower == "emergency" -> "This is an emergency!"
            lower == "ambulance" -> "Please call an ambulance!"
            lower == "heart attack" -> "Someone is having a heart attack. This is an emergency, please call an ambulance!"
            lower == "breathing" -> "I am having difficulty breathing."
            isSymptomWordEnglish(lower) -> "I have ${formatSymptomItemEnglish(lower)}."
            isEmergencyEventEnglish(lower) && tone == SentenceTone.CONCERNED -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "!")
            else -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, ".")
        }
    }

    private fun formatSingleConceptFilipino(token: String, tone: SentenceTone): String {
        val lower = token.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return when {
            isQuestionPhraseFilipino(lower) -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "?")
            isGreetingPhraseFilipino(lower) -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, ".")
            lower.contains("hindi ko maintindihan") -> "Hindi ko maintindihan."
            lower.contains("salamat") -> "Salamat."
            lower.contains("naiintindihan") -> "Naiintindihan ko."
            lower == "oo" -> "Oo."
            lower == "hindi" -> "Hindi."
            lower.contains("baka") || lower.contains("marahil") -> "Baka."
            lower.contains("pakiusap") -> "Pakiusap."
            lower.contains("may sakit") -> "May sakit ako."
            lower.contains("nasasaktan") || lower == "masakit" -> "Nasasaktan ako."
            lower.contains("buntis") -> "Buntis ako."
            lower.contains("panganganak") -> "Manganganak na ako."
            lower.contains("nahihilo") || lower.contains("pagkahilo") -> "Nahihilo ako."
            lower.contains("konsulta") -> "Nais kong kumonsulta sa doktor."
            lower.contains("gamot") -> "Kailangan ko ng gamot."
            lower.contains("reseta") -> "Kailangan ko ng reseta."
            lower.contains("x-ray") || lower.contains("xray") -> "Kailangan ko ng X-ray."
            lower.contains("iniksyon") -> "Kailangan ko ng iniksyon."
            lower.contains("swero") || lower.contains("iv") -> "Kailangan ko ng swero."
            lower.contains("operasyon") || lower.contains("surgery") -> "Kailangan ko ng operasyon."
            lower.contains("termometro") -> "Kailangan ko ng termometro."
            lower.contains("aksidente") -> "May aksidente!"
            lower.contains("emerhensya") -> "Emerhensya ito!"
            lower.contains("ambulansya") -> "Pakiusap tumawag ng ambulansya!"
            lower.contains("atake sa puso") -> "May inaatake sa puso, tumawag agad ng ambulansya!"
            lower.contains("paghinga") -> "Nahihirapan akong huminga."
            isSymptomWordFilipino(lower) -> "Mayroon akong ${formatFilipinoSymptomItem(lower)}."
            isEmergencyEventFilipino(lower) && tone == SentenceTone.CONCERNED -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "!")
            else -> ensurePunctuation(token.replaceFirstChar { it.uppercase() }, ".")
        }
    }

    private fun isStateWordEnglish(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return lower in listOf("sick", "hurt", "pregnant", "giving birth", "dizziness")
    }

    private fun formatStateEnglish(states: List<String>): String {
        val mapped = states.map { s ->
            when (s.lowercase(Locale.ENGLISH).trim().replace('_', ' ')) {
                "sick" -> "sick"
                "hurt" -> "hurt"
                "pregnant" -> "pregnant"
                "giving birth" -> "giving birth"
                "dizziness" -> "feeling dizzy"
                else -> s
            }
        }
        return "I am ${mapped.joinToString(" and ")}"
    }

    private fun isSymptomWordEnglish(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return lower in listOf(
            "headache", "fever", "cough", "sore throat", "dry mouth", "neck pain",
            "blurred vision", "broken bone", "asthma", "allergies", "cancer",
            "depression", "diabetes", "diarrhea", "constipation", "flu", "hypertension",
            "mumps", "pigsa", "sipon", "covid-19", "covid 19", "injury", "menstruation"
        )
    }

    private fun formatSymptomItemEnglish(symptom: String): String {
        val l = symptom.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return when (l) {
            "headache" -> "a headache"
            "fever" -> "a fever"
            "cough" -> "a cough"
            "sore throat" -> "a sore throat"
            "broken bone" -> "a broken bone"
            "dry mouth" -> "a dry mouth"
            "flu" -> "the flu"
            "boil", "pigsa" -> "a boil (pigsa)"
            "sipon" -> "a cold (sipon)"
            "injury" -> "an injury"
            else -> l
        }
    }

    private fun formatSymptomListEnglish(symptoms: List<String>): String {
        val mapped = symptoms.map { formatSymptomItemEnglish(it) }
        return when (mapped.size) {
            0 -> ""
            1 -> mapped[0]
            2 -> "${mapped[0]} and ${mapped[1]}"
            else -> "${mapped.dropLast(1).joinToString(", ")}, and ${mapped.last()}"
        }
    }

    private fun isNeedWordEnglish(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return lower in listOf(
            "medicines", "medicine", "prescription", "xray", "x-ray", "laboratory",
            "injection", "iv", "surgery", "thermometer", "compression", "circumcision", "abortion"
        )
    }

    private fun formatNeedItemEnglish(need: String): String {
        val l = need.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return when (l) {
            "medicines", "medicine" -> "medicine"
            "prescription" -> "my prescription"
            "xray", "x-ray" -> "an X-ray"
            "laboratory" -> "laboratory tests"
            "injection" -> "an injection"
            "iv" -> "an IV"
            "surgery" -> "surgery"
            "thermometer" -> "a thermometer"
            "compression" -> "compression"
            "circumcision" -> "a circumcision consultation"
            "abortion" -> "an abortion consultation"
            else -> l
        }
    }

    private fun formatNeedListEnglish(needs: List<String>): String {
        val mapped = needs.map { formatNeedItemEnglish(it) }
        return when (mapped.size) {
            0 -> ""
            1 -> mapped[0]
            2 -> "${mapped[0]} and ${mapped[1]}"
            else -> "${mapped.dropLast(1).joinToString(", ")}, and ${mapped.last()}"
        }
    }

    private fun isEmergencyEventEnglish(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return lower in listOf("accident", "emergency", "ambulance", "heart attack")
    }

    private fun isStateWordFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return lower in listOf(
            "masakit", "nasasaktan", "may sakit", "buntis", "panganganak",
            "nahihilo", "pagkahilo"
        )
    }

    private fun formatStateFilipino(states: List<String>): String {
        val mapped = states.map { s ->
            val l = s.lowercase(Locale.ROOT).trim().replace('_', ' ')
            when {
                l == "masakit" || l == "nasasaktan" -> "nasasaktan ako"
                l == "may sakit" -> "may sakit ako"
                l == "buntis" -> "buntis ako"
                l == "panganganak" -> "manganganak na ako"
                l == "nahihilo" || l == "pagkahilo" -> "nahihilo ako"
                else -> "$l ako"
            }
        }
        return mapped.joinToString(" at ")
    }

    private fun isSymptomWordFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return lower in listOf(
            "sakit ng ulo", "lagnat", "ubo", "namamagang lalamunan", "sakit sa lalamunan",
            "tuyong bibig", "sakit sa leeg", "malabong paningin", "bali sa buto", "hika",
            "alerhiya", "kanser", "depresyon", "diyabetis", "pagtatae", "hindi makadumi",
            "pagtitibi", "trangkaso", "altapresyon", "beke", "pigsa", "sipon", "covid-19",
            "pinsala", "sugat", "regla"
        )
    }

    private fun formatFilipinoSymptomItem(symptom: String): String {
        return symptom.lowercase(Locale.ROOT).trim().replace('_', ' ')
    }

    private fun formatSymptomListFilipino(symptoms: List<String>): String {
        val mapped = symptoms.map { formatFilipinoSymptomItem(it) }
        return when (mapped.size) {
            0 -> ""
            1 -> mapped[0]
            2 -> "${mapped[0]} at ${mapped[1]}"
            else -> "${mapped.dropLast(1).joinToString(", ")}, at ${mapped.last()}"
        }
    }

    private fun isNeedWordFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return lower in listOf(
            "gamot", "reseta", "x-ray", "xray", "iniksyon", "swero", "iv",
            "operasyon", "surgery", "termometro", "kompression", "tuli",
            "pagsusuri sa laboratoryo", "laboratoryo", "pagpapalaglag"
        )
    }

    private fun formatNeedItemFilipino(need: String): String {
        val l = need.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return when {
            l.contains("gamot") -> "gamot"
            l.contains("reseta") -> "reseta"
            l.contains("x-ray") || l.contains("xray") -> "X-ray"
            l.contains("iniksyon") -> "iniksyon"
            l.contains("swero") || l.contains("iv") -> "swero"
            l.contains("operasyon") || l.contains("surgery") -> "operasyon"
            l.contains("termometro") -> "termometro"
            l.contains("laboratoryo") || l.contains("pagsusuri") -> "pagsusuri sa laboratoryo"
            l.contains("kompression") -> "kompression"
            l.contains("tuli") -> "tuli"
            l.contains("pagpapalaglag") -> "pagpapalaglag"
            else -> l
        }
    }

    private fun formatNeedListFilipino(needs: List<String>): String {
        val mapped = needs.map { formatNeedItemFilipino(it) }
        return when (mapped.size) {
            0 -> ""
            1 -> mapped[0]
            2 -> "${mapped[0]} at ${mapped[1]}"
            else -> "${mapped.dropLast(1).joinToString(", ")}, at ${mapped.last()}"
        }
    }

    private fun isEmergencyEventFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return lower in listOf("aksidente", "emerhensya", "ambulansya", "atake sa puso")
    }

    private fun ensurePunctuation(text: String, mark: String): String {
        val trimmed = text.trim()
        if (trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!")) {
            return trimmed
        }
        return "$trimmed$mark"
    }

    private fun isQuestionPhrase(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH)
        return lower.startsWith("how are you") || lower.startsWith("how can i help")
    }

    private fun isQuestionPhraseFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT)
        return lower.contains("kamusta ka") || lower.contains("paano kita matutulungan")
    }

    private fun isGreetingPhrase(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH)
        return lower.startsWith("good morning") || lower.startsWith("good afternoon") ||
                lower.startsWith("good evening") || lower == "hello" || lower == "hi"
    }

    private fun isGreetingPhraseFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT)
        return lower.startsWith("magandang umaga") || lower.startsWith("magandang hapon") ||
                lower.startsWith("magandang gabi") || lower == "kamusta"
    }

    private fun normalizeLabelKey(label: String): String {
        return label.trim().lowercase(Locale.ROOT).replace('_', ' ')
    }
}
