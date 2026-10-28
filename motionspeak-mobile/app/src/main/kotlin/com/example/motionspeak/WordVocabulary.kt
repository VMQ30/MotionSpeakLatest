package com.example.motionspeak

// Pure decisions for the word screens and "Try it yourself" (spec A 5–6).

const val VOCAB_WORDS = "words"
const val VOCAB_LETTERS = "letters"

const val CATEGORY_MEDICAL = "Medical"
const val CATEGORY_GENERAL = "General"

private val SINGLE_CAPITAL_LETTER = Regex("^[A-Z]$")

/**
 * Which model a word belongs to. The backend's `vocabulary` wins when it is one
 * of the two known values; otherwise (word banks cached before the field was
 * sent) the rule migration 007 used to backfill it: a single capital A–Z is a
 * letter, everything else is a word.
 */
internal fun effectiveVocabulary(word: WordBankWord): String =
    when (word.vocabulary?.lowercase()) {
        VOCAB_WORDS -> VOCAB_WORDS
        VOCAB_LETTERS -> VOCAB_LETTERS
        else -> if (SINGLE_CAPITAL_LETTER.matches(word.label)) VOCAB_LETTERS else VOCAB_WORDS
    }

/**
 * How a word's name is shown on its page. Labels are stored in whatever case the
 * admin typed, usually ALL CAPS ("GOOD AFTERNOON"), which read as shouting at
 * display size, so words get title case. Letters keep their stored case: NG is
 * one FSL letter written with two characters, and "Ng" would misname it.
 */
internal fun wordDisplayLabel(word: WordBankWord): String =
    if (effectiveVocabulary(word) == VOCAB_LETTERS) word.label else word.label.toTitleCase()

/**
 * The display form of a label when only the label is known (History entries and
 * the translator's result). Same fallback rule as [effectiveVocabulary]: a single
 * capital A–Z is a letter and stays as it is; anything else is a word, in title
 * case ("FEVER" -> "Fever").
 */
internal fun labelDisplay(label: String): String =
    if (SINGLE_CAPITAL_LETTER.matches(label)) label else label.toTitleCase()

/**
 * A Filipino translation in the same title case as the word it translates
 * ("MAGANDANG TANGHALI" -> "Magandang Tanghali"), however the admin typed it.
 */
internal fun filipinoDisplay(text: String): String = text.trim().toTitleCase()

/**
 * The vocabulary "Try it yourself" should switch the translator to, or null to
 * leave it as it opens (Words). Letters only when a letters model is loaded.
 */
internal fun translatorStartVocabulary(requested: String?, hasLetters: Boolean): PredictionService.Vocabulary? =
    if (requested == VOCAB_LETTERS && hasLetters) PredictionService.Vocabulary.LETTERS else null

enum class VocabularyFilter { ALL, MEDICAL, GENERAL }

/** Whether [word] belongs under the given Word Bank pill. */
internal fun matchesFilter(word: WordBankWord, filter: VocabularyFilter): Boolean = when (filter) {
    VocabularyFilter.ALL -> true
    VocabularyFilter.MEDICAL -> word.category.trim().equals(CATEGORY_MEDICAL, ignoreCase = true)
    VocabularyFilter.GENERAL -> word.category.trim().equals(CATEGORY_GENERAL, ignoreCase = true)
}

data class GridCategory(val name: String, val wordCount: Int)

/**
 * Word Bank's category cards for the selected filter pill. ALL keeps every
 * category in the given order, with total counts, including empty ones (as the
 * grid always has). MEDICAL / GENERAL count only the words that pill covers
 * (see [matchesFilter]) and drop categories left with none. Categories match
 * words case-insensitively.
 */
internal fun gridCategories(
    words: List<WordBankWord>,
    categoryNames: List<String>,
    filter: VocabularyFilter,
): List<GridCategory> {
    val counts = HashMap<String, Int>()
    for (w in words) {
        if (!matchesFilter(w, filter)) continue
        val key = w.category.lowercase()
        counts[key] = (counts[key] ?: 0) + 1
    }
    val all = categoryNames.map { GridCategory(it, counts[it.lowercase()] ?: 0) }
    return if (filter == VocabularyFilter.ALL) all else all.filter { it.wordCount > 0 }
}

private data class SeedWord(
    val id: Int,
    val label: String,
    val category: String,
    val filipino: String,
    val description: String
)

private val SEED_WORDS: List<SeedWord> = listOf(
    SeedWord(1, "abortion", CATEGORY_MEDICAL, "Pagpapalaglag", "Termination of a pregnancy before birth."),
    SeedWord(2, "accident", CATEGORY_MEDICAL, "Aksidente", "Unforeseen event causing injury or bodily damage."),
    SeedWord(3, "allergies", CATEGORY_MEDICAL, "Alerhiya", "Immune system hypersensitivity reaction to a substance."),
    SeedWord(4, "ambulance", CATEGORY_MEDICAL, "Ambulansya", "Emergency medical vehicle equipped to transport patients."),
    SeedWord(5, "asthma", CATEGORY_MEDICAL, "Hika", "Chronic respiratory disease causing difficulty in breathing."),
    SeedWord(6, "blurred_vision", CATEGORY_MEDICAL, "Malabong paningin", "Loss of visual sharpness or clarity."),
    SeedWord(7, "breathing", CATEGORY_MEDICAL, "Paghinga", "Inhaling oxygen and exhaling carbon dioxide."),
    SeedWord(8, "broken_bone", CATEGORY_MEDICAL, "Bali sa buto", "Fracture or break in the continuity of a bone."),
    SeedWord(9, "cancer", CATEGORY_MEDICAL, "Kanser", "Disease characterized by uncontrolled abnormal cell growth."),
    SeedWord(10, "circumcision", CATEGORY_MEDICAL, "Tuli", "Surgical removal of the foreskin."),
    SeedWord(11, "compression", CATEGORY_MEDICAL, "Kompression", "Application of pressure, as in chest compressions or wound dressing."),
    SeedWord(12, "constipation", CATEGORY_MEDICAL, "Pagtitibi", "Infrequent, difficult, or painful bowel movements."),
    SeedWord(13, "consult", CATEGORY_GENERAL, "Konsulta", "Seeking medical advice or evaluation from a healthcare professional."),
    SeedWord(14, "cough", CATEGORY_MEDICAL, "Ubo", "Sudden reflex that clears the throat and breathing passages."),
    SeedWord(15, "covid_19", CATEGORY_MEDICAL, "Covid-19", "Contagious respiratory disease caused by the SARS-CoV-2 coronavirus."),
    SeedWord(16, "depression", CATEGORY_MEDICAL, "Depresyon", "Mental health condition marked by persistent sadness and loss of interest."),
    SeedWord(17, "diabetes", CATEGORY_MEDICAL, "Diyabetis", "Metabolic disorder involving high blood sugar levels."),
    SeedWord(18, "diarrhea", CATEGORY_MEDICAL, "Pagtatae", "Abnormally frequent, loose, or liquid stools."),
    SeedWord(19, "dizziness", CATEGORY_MEDICAL, "Pagkahilo", "Sensation of spinning, unsteadiness, or lightheadedness."),
    SeedWord(20, "dry_mouth", CATEGORY_MEDICAL, "Tuyong bibig", "Oral condition of insufficient saliva production."),
    SeedWord(21, "emergency", CATEGORY_MEDICAL, "Emerhensya", "Urgent, unexpected situation needing immediate medical action."),
    SeedWord(22, "fever", CATEGORY_MEDICAL, "Lagnat", "Higher than normal body temperature."),
    SeedWord(23, "flu", CATEGORY_MEDICAL, "Trangkaso", "Common contagious respiratory infection caused by influenza viruses."),
    SeedWord(24, "giving_birth", CATEGORY_MEDICAL, "Panganganak", "The delivery of a baby from the mother's body."),
    SeedWord(25, "good afternoon", CATEGORY_GENERAL, "Magandang hapon", "A greeting used during the afternoon."),
    SeedWord(26, "good_evening", CATEGORY_GENERAL, "Magandang gabi", "A polite greeting used in the evening."),
    SeedWord(27, "good_morning", CATEGORY_GENERAL, "Magandang umaga", "A polite greeting used in the morning."),
    SeedWord(28, "headache", CATEGORY_MEDICAL, "Sakit ng ulo", "Pain in any region of the head or neck."),
    SeedWord(29, "heart_attack", CATEGORY_MEDICAL, "Atake sa puso", "Sudden interruption of blood flow to part of the heart muscle."),
    SeedWord(30, "hello", CATEGORY_GENERAL, "Kamusta", "A friendly universal greeting."),
    SeedWord(31, "hi", CATEGORY_GENERAL, "Kamusta", "An informal greeting."),
    SeedWord(32, "hospitalized", CATEGORY_MEDICAL, "Naospital", "Admitted into a hospital for inpatient care."),
    SeedWord(33, "how_are_you", CATEGORY_GENERAL, "Kamusta ka", "Asking about someone's condition or health."),
    SeedWord(34, "how_can_i_help_you", CATEGORY_GENERAL, "Paano kita matutulungan", "Offering assistance to another person."),
    SeedWord(35, "hurt", CATEGORY_MEDICAL, "Masakit", "Experiencing physical pain or bodily injury."),
    SeedWord(36, "hypertension", CATEGORY_MEDICAL, "Altapresyon", "High blood pressure exceeding normal clinical thresholds."),
    SeedWord(37, "i_dont_understand", CATEGORY_GENERAL, "Hindi ko maintindihan", "Indicating a lack of comprehension or need for clarification."),
    SeedWord(38, "injection", CATEGORY_MEDICAL, "Iniksyon", "Administering medication into body tissue via a hypodermic needle."),
    SeedWord(39, "injury", CATEGORY_MEDICAL, "Sugat / Pinsala", "Physical damage or trauma to the body caused by external force."),
    SeedWord(40, "iv", CATEGORY_MEDICAL, "Swero / IV", "Intravenous administration of fluids or medical treatments directly into a vein."),
    SeedWord(41, "laboratory", CATEGORY_MEDICAL, "Laboratoryo", "Facility equipped for clinical testing of diagnostic samples."),
    SeedWord(42, "maybe", CATEGORY_GENERAL, "Baka / Marahil", "Expressing possibility or uncertainty."),
    SeedWord(43, "medicines", CATEGORY_MEDICAL, "Gamot", "Substances used to treat, prevent, or diagnose diseases."),
    SeedWord(44, "menstruation", CATEGORY_MEDICAL, "Regla", "Regular discharge of blood and mucosal tissue from the inner lining of the uterus."),
    SeedWord(45, "mumps", CATEGORY_MEDICAL, "Beke", "Viral infection recognizable by painful swelling of the salivary glands."),
    SeedWord(46, "neck_pain", CATEGORY_MEDICAL, "Sakit sa leeg", "Discomfort or stiffness located in the cervical spine area."),
    SeedWord(47, "no", CATEGORY_GENERAL, "Hindi", "Negative response, refusal, or disagreement."),
    SeedWord(48, "pigsa", CATEGORY_MEDICAL, "Pigsa", "Painful, pus-filled skin inflammation or boil."),
    SeedWord(49, "please", CATEGORY_GENERAL, "Pakiusap", "Polite word used when requesting something."),
    SeedWord(50, "pregnant", CATEGORY_MEDICAL, "Buntis", "State of carrying developing offspring inside the womb."),
    SeedWord(51, "prescription", CATEGORY_MEDICAL, "Reseta", "Written health directive detailing medicine and dosage."),
    SeedWord(52, "sick", CATEGORY_MEDICAL, "May sakit", "Affected by physical disease or illness."),
    SeedWord(53, "sipon", CATEGORY_MEDICAL, "Sipon", "Common cold characterized by nasal discharge or congestion."),
    SeedWord(54, "sore_throat", CATEGORY_MEDICAL, "Sakit sa lalamunan", "Irritation or scratchiness in the throat, often worsened by swallowing."),
    SeedWord(55, "surgery", CATEGORY_MEDICAL, "Operasyon", "Medical procedure involving physical incision and operative repair."),
    SeedWord(56, "thank_you", CATEGORY_GENERAL, "Salamat", "Expression of gratitude and appreciation."),
    SeedWord(57, "thermometer", CATEGORY_MEDICAL, "Termometro", "Device used to measure body temperature."),
    SeedWord(58, "understand", CATEGORY_GENERAL, "Naiintindihan", "Comprehending the meaning, concept, or instruction."),
    SeedWord(59, "xray", CATEGORY_MEDICAL, "X-ray", "Electromagnetic radiation imaging used to view bones and internal tissues."),
    SeedWord(60, "yes", CATEGORY_GENERAL, "Oo", "Affirmative agreement or consent.")
)

val DEFAULT_WORD_BANK: List<WordBankWord> = SEED_WORDS.map { seed ->
    WordBankWord(
        id = seed.id,
        label = seed.label,
        description = seed.description,
        sign_type = "FSL",
        category = seed.category,
        thumbnail_url = null,
        video_url = null,
        filipino_translation = seed.filipino,
        vocabulary = VOCAB_WORDS
    )
}

fun defaultWordBank(): List<WordBankWord> = DEFAULT_WORD_BANK

fun defaultCategories(): List<CategoryItem> = listOf(
    CategoryItem(id = 1, name = CATEGORY_MEDICAL, description = "Medical signs", word_count = 45),
    CategoryItem(id = 2, name = CATEGORY_GENERAL, description = "General and conversational signs", word_count = 15)
)

internal fun normalizeLabelKey(label: String): String =
    label.trim().lowercase().replace('_', ' ')

/**
 * Ensures the word bank contains only the 60 signs supported by the custom model
 * and that every sign is strictly categorized as either Medical or General.
 */
fun filterAndNormalizeToLabels(incoming: List<WordBankWord>): List<WordBankWord> {
    val incomingMap = HashMap<String, WordBankWord>()
    for (word in incoming) {
        incomingMap[normalizeLabelKey(word.label)] = word
        incomingMap[word.label.trim().lowercase()] = word
    }

    return DEFAULT_WORD_BANK.map { seed ->
        val matched = incomingMap[normalizeLabelKey(seed.label)]
            ?: incomingMap[seed.label.trim().lowercase()]
        if (matched != null) {
            matched.copy(
                label = seed.label,
                category = seed.category,
                filipino_translation = matched.filipino_translation?.takeIf { it.isNotBlank() } ?: seed.filipino_translation,
                description = matched.description?.takeIf { it.isNotBlank() } ?: seed.description,
                vocabulary = VOCAB_WORDS
            )
        } else {
            seed
        }
    }
}
