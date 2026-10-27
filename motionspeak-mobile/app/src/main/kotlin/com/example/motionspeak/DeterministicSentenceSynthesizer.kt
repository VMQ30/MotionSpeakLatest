package com.example.motionspeak

import java.util.Locale

/**
 * Deterministic sentence synthesizer.
 * Serves as the primary offline fallback when the LLM is absent, disabled, or fails.
 * Produces structured, punctuated, and grammatically consistent sentences in English and Filipino
 * based strictly on the recognized labels and known word bank definitions.
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

        val text = when (language) {
            SentenceLanguage.ENGLISH -> composeEnglish(tokens, tone)
            SentenceLanguage.FILIPINO -> composeFilipino(tokens, tone)
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

    private fun composeEnglish(tokens: List<String>, tone: SentenceTone): String {
        val normalized = tokens.map { normalizeEnglishToken(it) }

        // Special handling for single question / greeting / negation labels
        if (normalized.size == 1) {
            val single = normalized.first()
            return formatSingleConceptEnglish(single, tone)
        }

        // Handle negation patterns like ["no", "allergies"] -> "I have no allergies."
        if (normalized.first().equals("no", ignoreCase = true) && normalized.size == 2) {
            return "I have no ${normalized[1].lowercase(Locale.ENGLISH)}."
        }

        // Build composite sentence
        val sentences = mutableListOf<String>()
        val currentClause = mutableListOf<String>()

        for (token in normalized) {
            if (isQuestionPhrase(token)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatEnglishClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add(ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "?"))
            } else if (isGreetingPhrase(token)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatEnglishClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add(ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "."))
            } else if (token.equals("I don't understand", ignoreCase = true)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatEnglishClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add("I do not understand.")
            } else if (token.equals("thank you", ignoreCase = true)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatEnglishClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add("Thank you.")
            } else {
                currentClause.add(token)
            }
        }

        if (currentClause.isNotEmpty()) {
            val isUrgent = tone == SentenceTone.CONCERNED && currentClause.any { isEmergencyWord(it) }
            val endingPunctuation = if (isUrgent) "!" else "."
            sentences.add(formatEnglishClause(currentClause, endingPunctuation))
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

        val sentences = mutableListOf<String>()
        val currentClause = mutableListOf<String>()

        for (token in normalized) {
            if (isQuestionPhraseFilipino(token)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatFilipinoClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add(ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "?"))
            } else if (isGreetingPhraseFilipino(token)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatFilipinoClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add(ensurePunctuation(token.replaceFirstChar { it.uppercase() }, "."))
            } else if (token.equals("Hindi ko maintindihan", ignoreCase = true)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatFilipinoClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add("Hindi ko maintindihan.")
            } else if (token.equals("Salamat", ignoreCase = true)) {
                if (currentClause.isNotEmpty()) {
                    sentences.add(formatFilipinoClause(currentClause, "."))
                    currentClause.clear()
                }
                sentences.add("Salamat.")
            } else {
                currentClause.add(token)
            }
        }

        if (currentClause.isNotEmpty()) {
            val isUrgent = tone == SentenceTone.CONCERNED && currentClause.any { isEmergencyWordFilipino(it) }
            val endingPunctuation = if (isUrgent) "!" else "."
            sentences.add(formatFilipinoClause(currentClause, endingPunctuation))
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
        return filipinoMap[key] ?: raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
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

    private fun formatEnglishClause(words: List<String>, endingPunctuation: String): String {
        if (words.isEmpty()) return ""
        val lowers = words.map { it.lowercase(Locale.ENGLISH).trim().replace('_', ' ') }

        val hasPlease = lowers.contains("please")
        val hasConsult = lowers.contains("consult")
        val hasHospital = lowers.contains("hospital") || lowers.contains("hospitalized")
        val hasBreathing = lowers.contains("breathing")

        val states = lowers.filter { isStateWordEnglish(it) }
        val symptoms = lowers.filter { isSymptomWordEnglish(it) }
        val needs = lowers.filter { isNeedWordEnglish(it) }
        val emergencies = lowers.filter { isEmergencyEventEnglish(it) }

        // 1. Emergency events with details (accident, heart attack, ambulance, emergency)
        val hasAccident = emergencies.contains("accident")
        val hasEmergency = emergencies.contains("emergency")
        val hasAmbulance = emergencies.contains("ambulance")
        val hasHeartAttack = emergencies.contains("heart attack")

        if (hasHeartAttack || hasAccident || hasEmergency || (hasAmbulance && states.isEmpty() && symptoms.isEmpty())) {
            return when {
                hasHeartAttack -> "Someone is having a heart attack. This is an emergency, please call an ambulance!"
                hasAccident && hasBreathing && hasEmergency -> "There has been an accident, and someone is having difficulty breathing. This is an emergency!"
                hasAccident && hasAmbulance && hasEmergency -> "There has been an accident. This is an emergency, please call an ambulance!"
                hasAccident && hasAmbulance -> "There has been an accident, please call an ambulance!"
                hasAccident && states.isNotEmpty() -> "There has been an accident and ${formatStateEnglish(states).lowercase(Locale.ENGLISH)}!"
                hasAccident && symptoms.isNotEmpty() -> "There has been an accident and someone has ${formatSymptomListEnglish(symptoms)}!"
                hasAccident && hasEmergency -> "There has been an accident. This is an emergency!"
                hasAccident -> "There has been an accident!"
                hasEmergency && hasAmbulance -> "This is an emergency, please call an ambulance!"
                hasEmergency -> "This is an emergency!"
                hasAmbulance -> "Please call an ambulance!"
                else -> "Emergency!"
            }
        }

        // 2. State + Symptoms/Conditions (e.g. "hurt, broken bone" -> "I am hurt and have a broken bone.")
        if (states.isNotEmpty()) {
            val statePart = formatStateEnglish(states)
            if (symptoms.isNotEmpty()) {
                val symptomPart = formatSymptomListEnglish(symptoms)
                return if (states.contains("sick") && states.size == 1) {
                    "I am sick with $symptomPart."
                } else if (hasHospital) {
                    "$statePart and have $symptomPart, and need to be hospitalized."
                } else if (needs.isNotEmpty()) {
                    "$statePart, have $symptomPart, and need ${formatNeedListEnglish(needs)}."
                } else {
                    "$statePart and have $symptomPart."
                }
            } else if (hasConsult && hasHospital) {
                return "$statePart and want to consult a doctor at the hospital."
            } else if (hasConsult) {
                return "$statePart and want to consult a doctor."
            } else if (hasHospital) {
                return "$statePart and need to be hospitalized."
            } else if (hasBreathing) {
                return "$statePart and having difficulty breathing."
            } else if (needs.isNotEmpty()) {
                return "$statePart and need ${formatNeedListEnglish(needs)}."
            } else if (states.size == 1) {
                return "$statePart."
            } else {
                return "I am ${states.joinToString(" and ")}."
            }
        }

        // 3. Symptoms + Needs / Consult / Breathing / Hospital
        if (symptoms.isNotEmpty()) {
            val symptomPart = formatSymptomListEnglish(symptoms)
            if (needs.isNotEmpty()) {
                val needPart = formatNeedListEnglish(needs)
                return "I have $symptomPart. Please give me $needPart."
            }
            if (hasConsult) {
                return if (hasHospital) {
                    "I have $symptomPart and want to consult a doctor at the hospital."
                } else {
                    "I have $symptomPart and want to consult a doctor."
                }
            }
            if (hasBreathing) {
                return "I have $symptomPart and difficulty breathing."
            }
            if (hasHospital) {
                return "I have $symptomPart and need to be admitted to the hospital."
            }
            return "I have $symptomPart."
        }

        // 4. Consultations & Medical requests (no symptoms, no states)
        if (hasConsult) {
            return when {
                hasPlease && hasHospital -> "Please, I want to consult a doctor at the hospital."
                hasPlease -> "Please, I want to consult."
                hasHospital -> "I want to consult a doctor at the hospital."
                else -> "I want to consult a doctor."
            }
        }

        if (needs.isNotEmpty()) {
            val needPart = formatNeedListEnglish(needs)
            return if (hasPlease) {
                "Please give me $needPart."
            } else {
                "I need $needPart."
            }
        }

        if (hasBreathing) {
            return "I am having difficulty breathing."
        }

        if (hasHospital) {
            return "I need to be admitted to the hospital."
        }

        if (hasPlease && words.size == 1) {
            return "Please."
        }

        // 5. Safe fallback: never drop any words
        val cap = words.mapIndexed { idx, w ->
            if (idx == 0) w.replaceFirstChar { it.uppercase() } else w.lowercase(Locale.ENGLISH)
        }
        return when (cap.size) {
            1 -> ensurePunctuation(cap[0], endingPunctuation)
            2 -> "${cap[0]} and ${cap[1]}$endingPunctuation"
            else -> "${cap.dropLast(1).joinToString(", ")}, and ${cap.last()}$endingPunctuation"
        }
    }

    private fun formatFilipinoClause(words: List<String>, endingPunctuation: String): String {
        if (words.isEmpty()) return ""
        val lowers = words.map { it.lowercase(Locale.ROOT).trim().replace('_', ' ') }

        val hasPlease = lowers.any { it.contains("pakiusap") || it.contains("paki") }
        val hasConsult = lowers.any { it.contains("konsulta") }
        val hasHospital = lowers.any { it.contains("ospital") || it.contains("naospital") }
        val hasBreathing = lowers.any { it.contains("paghinga") }

        val states = lowers.filter { isStateWordFilipino(it) }
        val symptoms = lowers.filter { isSymptomWordFilipino(it) }
        val needs = lowers.filter { isNeedWordFilipino(it) }
        val emergencies = lowers.filter { isEmergencyEventFilipino(it) }

        // 1. Emergencies
        val hasAccident = emergencies.any { it.contains("aksidente") }
        val hasEmergency = emergencies.any { it.contains("emerhensya") }
        val hasAmbulance = emergencies.any { it.contains("ambulansya") }
        val hasHeartAttack = emergencies.any { it.contains("atake") }

        if (hasHeartAttack || hasAccident || hasEmergency || (hasAmbulance && states.isEmpty() && symptoms.isEmpty())) {
            return when {
                hasHeartAttack -> "May inaatake sa puso, tumawag agad ng ambulansya!"
                hasAccident && hasAmbulance && hasEmergency -> "May aksidente. Emerhensya ito, tumawag ng ambulansya!"
                hasAccident && hasAmbulance -> "May aksidente, pakiusap tumawag ng ambulansya!"
                hasAccident && states.isNotEmpty() -> "May aksidente at ${formatStateFilipino(states)}!"
                hasAccident && symptoms.isNotEmpty() -> "May aksidente at may ${formatSymptomListFilipino(symptoms)}!"
                hasAccident && hasEmergency -> "May aksidente. Emerhensya ito!"
                hasAccident -> "May aksidente!"
                hasEmergency && hasAmbulance -> "Emerhensya ito, tumawag ng ambulansya!"
                hasEmergency -> "Emerhensya ito!"
                hasAmbulance -> "Pakiusap tumawag ng ambulansya!"
                else -> "Emerhensya!"
            }
        }

        // 2. State + Symptoms (e.g. "nasasaktan, bali sa buto" -> "Nasasaktan ako at may bali sa buto.")
        if (states.isNotEmpty()) {
            val statePart = formatStateFilipino(states)
            if (symptoms.isNotEmpty()) {
                val symptomPart = formatSymptomListFilipino(symptoms)
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at may $symptomPart."
            } else if (hasConsult && hasHospital) {
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at nais kong kumonsulta sa doktor sa ospital."
            } else if (hasConsult) {
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at nais kong kumonsulta sa doktor."
            } else if (hasHospital) {
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at kailangang maospital."
            } else if (hasBreathing) {
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at nahihirapang huminga."
            } else if (needs.isNotEmpty()) {
                val capitalizedState = statePart.replaceFirstChar { it.uppercase() }
                return "$capitalizedState at kailangan ng ${formatNeedListFilipino(needs)}."
            } else {
                return "${statePart.replaceFirstChar { it.uppercase() }}."
            }
        }

        // 3. Symptoms + Needs / Consult / Breathing / Hospital
        if (symptoms.isNotEmpty()) {
            val symptomPart = formatSymptomListFilipino(symptoms)
            if (needs.isNotEmpty()) {
                return "Mayroon akong $symptomPart. Pakiusap bigyan ako ng gamot."
            }
            if (hasConsult) {
                return if (hasHospital) {
                    "May $symptomPart ako at nais kong kumonsulta sa doktor sa ospital."
                } else {
                    "May $symptomPart ako at nais kong kumonsulta sa doktor."
                }
            }
            if (hasBreathing) {
                return "May $symptomPart ako at nahihirapang huminga."
            }
            if (hasHospital) {
                return "May $symptomPart ako at kailangang maospital."
            }
            return "Mayroon akong $symptomPart."
        }

        // 4. Consultations & Requests
        if (hasConsult) {
            return when {
                hasPlease && hasHospital -> "Pakiusap, nais kong kumonsulta sa doktor sa ospital."
                hasPlease -> "Pakiusap, nais kong kumonsulta."
                hasHospital -> "Nais kong kumonsulta sa doktor sa ospital."
                else -> "Nais kong kumonsulta sa doktor."
            }
        }

        if (needs.isNotEmpty()) {
            val needPart = formatNeedListFilipino(needs)
            return if (hasPlease) {
                "Pakiusap bigyan ako ng $needPart."
            } else {
                "Kailangan ko ng $needPart."
            }
        }

        if (hasBreathing) {
            return "Nahihirapan akong huminga."
        }

        if (hasHospital) {
            return "Kailangan kong maospital."
        }

        if (hasPlease && words.size == 1) {
            return "Pakiusap."
        }

        // 5. Safe fallback
        val cap = words.mapIndexed { idx, w ->
            if (idx == 0) w.replaceFirstChar { it.uppercase() } else w.lowercase(Locale.ROOT)
        }
        return when (cap.size) {
            1 -> ensurePunctuation(cap[0], endingPunctuation)
            2 -> "${cap[0]} at ${cap[1]}$endingPunctuation"
            else -> "${cap.dropLast(1).joinToString(", ")}, at ${cap.last()}$endingPunctuation"
        }
    }

    private fun isStateWordEnglish(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH).trim().replace('_', ' ')
        return lower in listOf("sick", "hurt", "pregnant", "giving birth")
    }

    private fun formatStateEnglish(states: List<String>): String {
        val mapped = states.map { s ->
            when (s.lowercase(Locale.ENGLISH).trim().replace('_', ' ')) {
                "sick" -> "sick"
                "hurt" -> "hurt"
                "pregnant" -> "pregnant"
                "giving birth" -> "giving birth"
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
            "mumps", "pigsa", "sipon", "covid-19", "injury", "menstruation", "dizziness"
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
            "injection", "iv", "surgery", "thermometer", "compression", "circumcision"
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
            "pagsusuri sa laboratoryo", "laboratoryo"
        )
    }

    private fun formatNeedItemFilipino(need: String): String {
        val l = need.lowercase(Locale.ROOT).trim().replace('_', ' ')
        return when (l) {
            "gamot" -> "gamot"
            "reseta" -> "reseta"
            "x-ray", "xray" -> "X-ray"
            "iniksyon" -> "iniksyon"
            "swero", "iv" -> "swero"
            "operasyon", "surgery" -> "operasyon"
            "termometro" -> "termometro"
            "laboratoryo" -> "pagsusuri sa laboratoryo"
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

    private fun isEmergencyWord(token: String): Boolean {
        val lower = token.lowercase(Locale.ENGLISH)
        return lower in listOf("emergency", "accident", "ambulance", "heart attack", "injury", "hurt", "breathing")
    }

    private fun isEmergencyWordFilipino(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT)
        return lower in listOf("emerhensya", "aksidente", "ambulansya", "atake sa puso", "pinsala", "sugat", "masakit", "paghinga")
    }
}
