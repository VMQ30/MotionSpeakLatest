package com.example.motionspeak

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LlmSentenceSynthesisTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── 1. Prompt Builder Tests ──────────────────────────────────────────────

    @Test
    fun testPromptBuilderEnglishStructure() {
        val tokens = listOf("headache", "fever", "medicines")
        val prompt = LlmPromptBuilder.buildPrompt(tokens, SentenceTone.NEUTRAL, SentenceLanguage.ENGLISH)

        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.contains("<start_of_turn>model"))
        assertTrue(prompt.contains("Input: headache, fever, medicines"))
        assertTrue(prompt.contains("Strict Rules:"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("ZERO WORD LOSS"))
        assertTrue(prompt.contains("Preserve negation strictly"))
    }

    @Test
    fun testPromptBuilderFilipinoStructure() {
        val tokens = listOf("no", "allergies")
        val prompt = LlmPromptBuilder.buildPrompt(tokens, SentenceTone.CONCERNED, SentenceLanguage.FILIPINO)

        assertTrue(prompt.contains("Filipino"))
        assertTrue(prompt.contains("concerned"))
        assertTrue(prompt.contains("Input: no, allergies"))
        assertTrue(prompt.contains("Halimbawa:"))
        assertTrue(prompt.contains("ZERO WORD LOSS"))
    }

    @Test
    fun testSanitizeOutputStripsMarkdownAndQuotes() {
        val raw1 = "```markdown\n\"I have a headache.\"\n```"
        assertEquals("I have a headache.", LlmPromptBuilder.sanitizeOutput(raw1))

        val raw2 = "Sentence: Good morning, how can I help you?"
        assertEquals("Good morning, how can I help you?", LlmPromptBuilder.sanitizeOutput(raw2))

        val raw3 = "   Output: There has been an accident.   \nHere is an explanation of the accident."
        assertEquals("There has been an accident.", LlmPromptBuilder.sanitizeOutput(raw3))

        val raw4 = "Hindi ko maintindihan"
        assertEquals("Hindi ko maintindihan.", LlmPromptBuilder.sanitizeOutput(raw4))
    }

    @Test
    fun testPromptBuilderWithEmotionAndMarkers() {
        val tokens = listOf("consult", "doctor")
        val markers = LinguisticMarkers(isQuestion = true, isNegation = false)
        val prompt = LlmPromptBuilder.buildPrompt(tokens, SentenceTone.CONCERNED, SentenceLanguage.ENGLISH, markers)

        assertTrue(prompt.contains("Context tone: concerned."))
        assertTrue(prompt.contains("Facial Non-Manual Marker: Question / Inquiry (Raised eyebrows detected)."))
        assertTrue(prompt.contains("EMOTION & TONE SYNERGY:"))
        assertTrue(prompt.contains("FACIAL GRAMMATICAL MARKERS & PUNCTUATION:"))
    }

    @Test
    fun testPromptBuilderWithNegationMarker() {
        val tokens = listOf("fever")
        val markers = LinguisticMarkers(isQuestion = false, isNegation = true)
        val prompt = LlmPromptBuilder.buildPrompt(tokens, SentenceTone.NEGATIVE, SentenceLanguage.FILIPINO, markers)

        assertTrue(prompt.contains("Context tone: negative."))
        assertTrue(prompt.contains("Facial Non-Manual Marker: Negation / Denial (Furrowed eyebrows detected)."))
    }

    @Test
    fun testSanitizeOutputPunctuationSynergy() {
        // Question marker forces ?
        val statement = "I want to consult a doctor."
        val questionResult = LlmPromptBuilder.sanitizeOutput(
            raw = statement,
            markers = LinguisticMarkers(isQuestion = true),
            tone = SentenceTone.NEUTRAL
        )
        assertEquals("I want to consult a doctor?", questionResult)

        // Concerned tone + emergency text forces !
        val emergencyText = "There has been an accident."
        val urgentResult = LlmPromptBuilder.sanitizeOutput(
            raw = emergencyText,
            markers = LinguisticMarkers(isQuestion = false),
            tone = SentenceTone.CONCERNED
        )
        assertEquals("There has been an accident!", urgentResult)

        // Filipino concerned tone + emergency forces !
        val filipinoEmergency = "May naganap na aksidente."
        val filUrgentResult = LlmPromptBuilder.sanitizeOutput(
            raw = filipinoEmergency,
            markers = LinguisticMarkers(isQuestion = false),
            tone = SentenceTone.CONCERNED
        )
        assertEquals("May naganap na aksidente!", filUrgentResult)

        // Neutral tone softens exclamation to a calm declarative period
        val exclamationEmergency = "There has been an accident!"
        val neutralResult = LlmPromptBuilder.sanitizeOutput(
            raw = exclamationEmergency,
            markers = LinguisticMarkers(isQuestion = false),
            tone = SentenceTone.NEUTRAL
        )
        assertEquals("There has been an accident.", neutralResult)
    }

    @Test
    fun testPromptBuilderFewShotContainsToneDirectives() {
        val promptEnglish = LlmPromptBuilder.buildPrompt(
            tokens = listOf("hurt", "broken_bone"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertTrue(promptEnglish.contains("Context tone: concerned"))
        assertTrue(promptEnglish.contains("Context tone: positive"))
        assertTrue(promptEnglish.contains("Context tone: neutral"))
        assertTrue(promptEnglish.contains("Context tone: negative"))
        // Confirms structural restructuring guidance is present
        assertTrue(promptEnglish.contains("DYNAMIC SENTENCE STRUCTURE (NO FIXED TEMPLATES)"))
        assertTrue(promptEnglish.contains("Sentence Restructuring"))
        assertTrue(promptEnglish.contains("Please help, I am hurt and have a broken bone!"))
        assertTrue(promptEnglish.contains("Please help, there has been an accident! I am hurt and have a broken bone!"))

        val promptFilipino = LlmPromptBuilder.buildPrompt(
            tokens = listOf("hurt", "broken_bone"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertTrue(promptFilipino.contains("Context tone: concerned"))
        assertTrue(promptFilipino.contains("Context tone: positive"))
        assertTrue(promptFilipino.contains("Context tone: neutral"))
        assertTrue(promptFilipino.contains("Context tone: negative"))
        assertTrue(promptFilipino.contains("Tulungan ninyo ako, nasasaktan ako at may bali sa buto!"))
        assertTrue(promptFilipino.contains("Saklolo, may aksidente! Nasasaktan ako at may bali sa buto!"))
    }

    @Test
    fun testFewShotExamplesPreserveFullTokenCoverage() {
        // English few-shot structural sentences
        val enHurtConcerned = "Please help, I am hurt and have a broken bone!"
        assertTrue(WordTokenMatcher.isCovered("hurt", enHurtConcerned))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", enHurtConcerned))

        val enAccidentHospitalConcerned = "There has been an accident with a broken bone! Call an ambulance immediately, I must be hospitalized!"
        assertTrue(WordTokenMatcher.isCovered("accident", enAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", enAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("ambulance", enAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("hospitalized", enAccidentHospitalConcerned))

        val enPositive = "Good morning! I am feeling sick and would appreciate consulting a doctor. Thank you so much!"
        assertTrue(WordTokenMatcher.isCovered("good_morning", enPositive))
        assertTrue(WordTokenMatcher.isCovered("sick", enPositive))
        assertTrue(WordTokenMatcher.isCovered("consult", enPositive))
        assertTrue(WordTokenMatcher.isCovered("thank_you", enPositive))

        // Filipino few-shot structural sentences
        val filHurtConcerned = "Tulungan ninyo ako, nasasaktan ako at may bali sa buto!"
        assertTrue(WordTokenMatcher.isCovered("hurt", filHurtConcerned))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", filHurtConcerned))

        val filAccidentHospitalConcerned = "May naganap na aksidente at may bali sa buto! Tumawag agad ng ambulansya, kailangang maospital kaagad!"
        assertTrue(WordTokenMatcher.isCovered("accident", filAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", filAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("ambulance", filAccidentHospitalConcerned))
        assertTrue(WordTokenMatcher.isCovered("hospitalized", filAccidentHospitalConcerned))
    }

    // ── 2. Word Token Matcher & Zero-Word-Loss Tests ─────────────────────────

    @Test
    fun testWordTokenMatcherCoverageEnglish() {
        val text = "I am hurt and have a broken bone. Please call an ambulance, I need to be admitted to the hospital."
        assertTrue(WordTokenMatcher.isCovered("hurt", text))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", text))
        assertTrue(WordTokenMatcher.isCovered("ambulance", text))
        assertTrue(WordTokenMatcher.isCovered("hospitalized", text))
        assertTrue(WordTokenMatcher.isCovered("please", text))

        assertFalse(WordTokenMatcher.isCovered("fever", text))
        assertFalse(WordTokenMatcher.isCovered("medicines", text))

        val missing = WordTokenMatcher.getMissingTokens(
            listOf("hurt", "broken_bone", "ambulance", "hospitalized", "fever"),
            text
        )
        assertEquals(listOf("fever"), missing)
    }

    @Test
    fun testWordTokenMatcherCoverageFilipino() {
        val text = "Nasasaktan ako at may bali sa buto. Pakiusap tumawag ng ambulansya, kailangan kong maospital."
        assertTrue(WordTokenMatcher.isCovered("hurt", text))
        assertTrue(WordTokenMatcher.isCovered("broken_bone", text))
        assertTrue(WordTokenMatcher.isCovered("ambulance", text))
        assertTrue(WordTokenMatcher.isCovered("hospitalized", text))
        assertTrue(WordTokenMatcher.isCovered("please", text))

        val missing = WordTokenMatcher.getMissingTokens(
            listOf("hurt", "broken_bone", "ambulance", "hospitalized"),
            text
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun testWordTokenMatcherCoverageRatio() {
        val text = "I have a headache and a fever."
        val tokens = listOf("headache", "fever", "medicines", "please")
        val ratio = WordTokenMatcher.getTokenCoverageRatio(tokens, text)
        assertEquals(0.5f, ratio, 0.01f)
    }

    // ── 3. SHA-256 and Integrity Verification Tests ──────────────────────────

    @Test
    fun testSha256Computation() {
        val testFile = tempFolder.newFile("sample_model.bin")
        testFile.writeText("MotionSpeak Gemma Test Weights")

        val hash = LlmModelManager.computeSha256(testFile)
        assertNotNull(hash)
        assertEquals(64, hash.length) // SHA-256 is 64 hex characters

        // Repeat produces same deterministic hash
        val hash2 = LlmModelManager.computeSha256(testFile)
        assertEquals(hash, hash2)
    }

    private class DummyContext : android.content.ContextWrapper(null)

    // ── 4. LlmSynthesizer Fallback Behavior ───────────────────────────────────

    @Test
    fun testLlmSynthesizerGracefulFallbackWhenModelAbsent() = runTest {
        val nonExistentFile = File(tempFolder.root, "absent_model.task")
        val fallback = DeterministicSentenceSynthesizer(defaultWordBank())

        val synthesizer = LlmSynthesizer(
            context = DummyContext(),
            modelFile = nonExistentFile,
            fallbackSynthesizer = fallback
        )

        assertFalse(synthesizer.isReady)

        val result = synthesizer.compose(
            tokens = listOf("no", "allergies"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )

        assertNotNull(result)
        assertEquals("I have no allergies.", result.text)
        assertFalse(result.isLlmGenerated)
        assertEquals(listOf("no", "allergies"), result.originalTokens)
    }

    @Test
    fun testLlmSynthesizerFilipinoFallbackWhenModelAbsent() = runTest {
        val nonExistentFile = File(tempFolder.root, "absent_model.task")
        val fallback = DeterministicSentenceSynthesizer(defaultWordBank())

        val synthesizer = LlmSynthesizer(
            context = DummyContext(),
            modelFile = nonExistentFile,
            fallbackSynthesizer = fallback
        )

        val result = synthesizer.compose(
            tokens = listOf("i_dont_understand"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )

        assertEquals("Hindi ko maintindihan.", result.text)
        assertFalse(result.isLlmGenerated)
    }
}
