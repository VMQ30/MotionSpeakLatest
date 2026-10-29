package com.example.motionspeak

import java.util.Locale

/**
 * Validates whether signed vocabulary tokens are preserved in synthesized sentences.
 * Covers English terms, Filipino terms, stems, and canonical synonyms for all 60 vocabulary words.
 */
object WordTokenMatcher {

    private val KEYWORD_VARIANTS: Map<String, List<String>> = mapOf(
        "abortion" to listOf("abortion", "pagpapalaglag", "palaglag"),
        "accident" to listOf("accident", "aksidente", "crash", "collision"),
        "allergies" to listOf("allerg", "alerhi"),
        "ambulance" to listOf("ambulance", "ambulansya"),
        "asthma" to listOf("asthma", "hika"),
        "blurred_vision" to listOf("blurred vision", "blurry vision", "malabong paningin", "malabo ang paningin", "malabo"),
        "breathing" to listOf("breath", "huminga", "paghinga", "respiratory"),
        "broken_bone" to listOf("broken bone", "fracture", "bali sa buto", "bali"),
        "cancer" to listOf("cancer", "kanser"),
        "circumcision" to listOf("circumcision", "tuli"),
        "compression" to listOf("compression", "kompression", "pressure"),
        "constipation" to listOf("constipat", "pagtitibi", "makadumi", "dumi"),
        "consult" to listOf("consult", "doctor", "doktor", "konsulta", "tingnan"),
        "cough" to listOf("cough", "ubo"),
        "covid_19" to listOf("covid", "covid-19", "coronavirus"),
        "depression" to listOf("depress", "depresyon"),
        "diabetes" to listOf("diabet", "diyabet"),
        "diarrhea" to listOf("diarrhea", "pagtatae", "nagtatae", "lbm"),
        "dizziness" to listOf("dizz", "lightheaded", "hilo", "pagkahilo", "nahihilo"),
        "dry_mouth" to listOf("dry mouth", "tuyong bibig", "tuyo ang bibig"),
        "emergency" to listOf("emergency", "emerhensya", "urgent"),
        "fever" to listOf("fever", "lagnat", "temperature"),
        "flu" to listOf("flu", "trangkaso"),
        "giving_birth" to listOf("giving birth", "birth", "labor", "delivery", "panganganak", "manganganak", "manganak"),
        "good afternoon" to listOf("good afternoon", "magandang hapon", "hapon"),
        "good_evening" to listOf("good evening", "magandang gabi", "gabi"),
        "good_morning" to listOf("good morning", "magandang umaga", "umaga"),
        "headache" to listOf("headache", "head pain", "sakit ng ulo", "sakit sa ulo", "ulo"),
        "heart_attack" to listOf("heart attack", "cardiac", "atake sa puso", "atake"),
        "hello" to listOf("hello", "hi", "kamusta"),
        "hi" to listOf("hi", "hello", "kamusta"),
        "hospitalized" to listOf("hospital", "admit", "admission", "ospital", "naospital", "maospital"),
        "how_are_you" to listOf("how are you", "kamusta ka", "kamusta"),
        "how_can_i_help_you" to listOf("how can i help", "help you", "matutulungan", "paano kita matutulungan"),
        "hurt" to listOf("hurt", "pain", "masakit", "nasasaktan"),
        "hypertension" to listOf("hypertension", "high blood", "altapresyon"),
        "i_dont_understand" to listOf("don't understand", "do not understand", "hindi ko maintindihan", "di maintindihan", "hindi maintindihan"),
        "injection" to listOf("injection", "shot", "iniksyon", "turok"),
        "injury" to listOf("injur", "wound", "sugat", "pinsala"),
        "iv" to listOf("iv", "drip", "swero", "intravenous"),
        "laboratory" to listOf("laborator", "lab test", "laboratoryo", "pagsusuri"),
        "maybe" to listOf("maybe", "perhaps", "baka", "marahil"),
        "medicines" to listOf("medicin", "medicat", "gamot", "drug", "cure"),
        "menstruation" to listOf("menstruat", "period", "regla"),
        "mumps" to listOf("mumps", "beke"),
        "neck_pain" to listOf("neck pain", "neck", "sakit sa leeg", "leeg"),
        "no" to listOf("no", "not", "none", "hindi", "wala"),
        "pigsa" to listOf("pigsa", "boil", "abscess"),
        "please" to listOf("please", "pakiusap", "paki"),
        "pregnant" to listOf("pregnant", "pregnancy", "buntis"),
        "prescription" to listOf("prescription", "reseta"),
        "sick" to listOf("sick", "ill", "unwell", "may sakit", "masama ang pakiramdam"),
        "sipon" to listOf("sipon", "cold", "runny nose"),
        "sore_throat" to listOf("sore throat", "throat", "sakit sa lalamunan", "namamagang lalamunan", "lalamunan"),
        "surgery" to listOf("surgery", "operat", "operasyon"),
        "thank_you" to listOf("thank", "salamat"),
        "thermometer" to listOf("thermometer", "termometro"),
        "understand" to listOf("understand", "naintindihan", "maintindihan", "nauunawaan"),
        "xray" to listOf("x-ray", "xray", "radiograph"),
        "yes" to listOf("yes", "yeah", "oo", "opo")
    )

    /**
     * Checks if a signed token (or its canonical concept / translation) appears in [text].
     */
    fun isCovered(token: String, text: String): Boolean {
        val lowerText = text.lowercase(Locale.ROOT)
        val key = token.trim().lowercase(Locale.ROOT).replace(' ', '_')
        val phrase = token.trim().lowercase(Locale.ROOT).replace('_', ' ')

        if (lowerText.contains(phrase)) return true

        val variants = KEYWORD_VARIANTS[key]
            ?: KEYWORD_VARIANTS[phrase]
            ?: listOf(phrase)

        return variants.any { lowerText.contains(it) }
    }

    /**
     * Returns the subset of [tokens] that do not have their concepts represented in [text].
     */
    fun getMissingTokens(tokens: List<String>, text: String): List<String> {
        return tokens.filterNot { isCovered(it, text) }
    }

    /**
     * Returns the fraction of [tokens] covered in [text] (0.0 to 1.0).
     */
    fun getTokenCoverageRatio(tokens: List<String>, text: String): Float {
        if (tokens.isEmpty()) return 1.0f
        val covered = tokens.count { isCovered(it, text) }
        return covered.toFloat() / tokens.size.toFloat()
    }
}
