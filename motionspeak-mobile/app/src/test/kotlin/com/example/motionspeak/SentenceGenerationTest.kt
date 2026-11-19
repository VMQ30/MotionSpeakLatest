package com.example.motionspeak

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceGenerationTest {

    private lateinit var synthesizer: DeterministicSentenceSynthesizer

    @Before
    fun setup() {
        synthesizer = DeterministicSentenceSynthesizer(defaultWordBank())
    }

    // ── A. Basic sentence generation (English) ───────────────────────────────

    @Test
    fun testEnglishHelloHowAreYou() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hello", "how_are_you"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("Hello. How are you?", result.text)
        assertFalse(result.isLlmGenerated)
        assertEquals(listOf("hello", "how_are_you"), result.originalTokens)
    }

    @Test
    fun testEnglishGoodMorningPleaseConsult() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("good_morning", "please", "consult"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("Good morning. Please, I want to consult.", result.text)
    }

    @Test
    fun testEnglishHeadacheFeverMedicines() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("headache", "fever", "medicines"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I have a headache and a fever. Please give me medicine.", result.text)
    }

    @Test
    fun testEnglishNoAllergies() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("no", "allergies"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I have no allergies.", result.text)
    }

    @Test
    fun testEnglishIDontUnderstand() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("i_dont_understand"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I do not understand.", result.text)
    }

    @Test
    fun testEnglishAccidentAmbulanceEmergency() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("There has been an accident. This is an emergency, please call an ambulance!", result.text)
    }

    @Test
    fun testEnglishHurtBrokenBone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hurt", "broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I am hurt and have a broken bone.", result.text)
    }

    @Test
    fun testEnglishHurtAlone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I am hurt.", result.text)
    }

    @Test
    fun testEnglishBrokenBoneAlone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I have a broken bone.", result.text)
    }

    @Test
    fun testEnglishPregnantHospitalConsult() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("pregnant", "hospitalized", "consult"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I am pregnant and want to consult a doctor at the hospital.", result.text)
    }

    @Test
    fun testEnglishThankYou() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("thank_you"),
            tone = SentenceTone.POSITIVE,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("Thank you.", result.text)
    }

    // ── B. Filipino generation ───────────────────────────────────────────────

    @Test
    fun testFilipinoHelloHowAreYou() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hello", "how_are_you"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Kamusta. Kamusta ka?", result.text)
        assertEquals(SentenceLanguage.FILIPINO, result.language)
    }

    @Test
    fun testFilipinoNoAllergies() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("no", "allergies"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Wala akong alerhiya.", result.text)
    }

    @Test
    fun testFilipinoIDontUnderstand() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("i_dont_understand"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Hindi ko maintindihan.", result.text)
    }

    @Test
    fun testFilipinoHeadacheFeverMedicines() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("headache", "fever", "medicines"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Mayroon akong sakit ng ulo at lagnat. Pakiusap bigyan ako ng gamot.", result.text)
    }

    @Test
    fun testFilipinoAccidentAmbulanceEmergency() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("May aksidente. Emerhensya ito, tumawag ng ambulansya!", result.text)
    }

    @Test
    fun testFilipinoHurtBrokenBone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hurt", "broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Nasasaktan ako at may bali sa buto.", result.text)
    }

    @Test
    fun testFilipinoHurtAlone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Nasasaktan ako.", result.text)
    }

    @Test
    fun testFilipinoBrokenBoneAlone() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Mayroon akong bali sa buto.", result.text)
    }

    @Test
    fun testFilipinoThankYou() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("thank_you"),
            tone = SentenceTone.POSITIVE,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Salamat.", result.text)
    }

    // ── C. Meaning preservation & Edge Cases ─────────────────────────────────

    @Test
    fun testEmptyTokensYieldsEmptyResult() = runTest {
        val result = synthesizer.compose(
            tokens = emptyList(),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("", result.text)
        assertTrue(result.originalTokens.isEmpty())
    }

    @Test
    fun testPreservesMedicalLabels() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("asthma", "cough", "medicines"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I have asthma and a cough. Please give me medicine.", result.text)
    }

    @Test
    fun testArbitraryCombinationPreservesAllWordsEnglish() = runTest {
        val tokens = listOf("accident", "broken_bone", "ambulance", "hospitalized")
        val result = synthesizer.compose(
            tokens = tokens,
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        val missing = WordTokenMatcher.getMissingTokens(tokens, result.text)
        assertTrue("Expected zero missing tokens, but missing: $missing in \"${result.text}\"", missing.isEmpty())
    }

    @Test
    fun testSixWordCombinationPreservesAllWordsEnglish() = runTest {
        val tokens = listOf("good_morning", "dizziness", "consult", "please", "medicines", "thank_you")
        val result = synthesizer.compose(
            tokens = tokens,
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val missing = WordTokenMatcher.getMissingTokens(tokens, result.text)
        assertTrue("Expected zero missing tokens, but missing: $missing in \"${result.text}\"", missing.isEmpty())
    }

    @Test
    fun testArbitraryCombinationPreservesAllWordsFilipino() = runTest {
        val tokens = listOf("accident", "broken_bone", "ambulance", "hospitalized")
        val result = synthesizer.compose(
            tokens = tokens,
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        val missing = WordTokenMatcher.getMissingTokens(tokens, result.text)
        assertTrue("Expected zero missing tokens, but missing: $missing in \"${result.text}\"", missing.isEmpty())
    }

    @Test
    fun testMultiSymptomNegationEnglish() = runTest {
        val tokens = listOf("no", "fever", "cough")
        val result = synthesizer.compose(
            tokens = tokens,
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I have no fever, but I have a cough.", result.text)
    }

    // ── D. SentenceComposer Buffering & Pause Detection ──────────────────────

    @Test
    fun testSentenceComposerPauseTriggersSynthesisWhenEnabled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedSentence: SentenceResult? = null
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            dedupeWindowMs = 0L, // disable dedupe for direct testing
            autoComposeEnabled = true
        )
        composer.onSentenceComposed = { result -> composedSentence = result }

        composer.addToken("headache")
        testScheduler.advanceTimeBy(100)
        composer.addToken("fever")

        // 1000ms later, pause has not yet expired (needs 2000ms)
        testScheduler.advanceTimeBy(1000)
        assertEquals(null, composedSentence)

        // Advance remaining 1100ms (total > 2000ms after last token)
        testScheduler.advanceTimeBy(1100)

        assertTrue(composedSentence != null)
        assertEquals("I have a headache and a fever.", composedSentence?.text)
        composer.close()
    }

    @Test
    fun testSentenceComposerDoesNotAutoComposeWhenDisabled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedSentence: SentenceResult? = null
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            dedupeWindowMs = 0L,
            autoComposeEnabled = false // Default: no auto composition after signing
        )
        composer.onSentenceComposed = { result -> composedSentence = result }

        composer.addToken("headache")
        testScheduler.advanceTimeBy(100)
        composer.addToken("fever")

        // 5 seconds pass: no sentence is generated automatically
        testScheduler.advanceTimeBy(5000)
        assertEquals(null, composedSentence)

        // Manual trigger (pressing the button) triggers sentence composition
        composer.triggerComposition()
        testScheduler.advanceTimeBy(100)

        assertTrue(composedSentence != null)
        assertEquals("I have a headache and a fever.", composedSentence?.text)
        composer.close()
    }

    @Test
    fun testSentenceComposerResetCancelsPending() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedCount = 0
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            autoComposeEnabled = true
        )
        composer.onSentenceComposed = { composedCount++ }

        composer.addToken("accident")
        testScheduler.advanceTimeBy(1000)
        composer.reset()

        testScheduler.advanceTimeBy(3000)
        assertEquals(0, composedCount)
        composer.close()
    }

    @Test
    fun testBackspacePreventsTokenResurrectionScenario() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedSentence: SentenceResult? = null
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            dedupeWindowMs = 0L,
            autoComposeEnabled = false
        )
        composer.onSentenceComposed = { result -> composedSentence = result }

        // User signs "constipation"
        composer.addToken("constipation")
        testScheduler.advanceTimeBy(100)

        // User backspaces "constipation"
        composer.removeLastToken()
        testScheduler.advanceTimeBy(100)

        // User signs "hello"
        composer.addToken("hello")
        testScheduler.advanceTimeBy(100)

        // User triggers composition
        composer.triggerComposition()
        testScheduler.advanceTimeBy(100)

        assertTrue(composedSentence != null)
        assertEquals(listOf("hello"), composedSentence?.originalTokens)
        assertEquals("Hello.", composedSentence?.text)
        composer.close()
    }

    @Test
    fun testComposerPropagatesT0AndPromptAndTone() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedSentence: SentenceResult? = null
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            dedupeWindowMs = 0L,
            autoComposeEnabled = false
        )
        composer.onSentenceComposed = { result -> composedSentence = result }

        val testPrompt = SentencePrompt(
            promptText = "I HAVE HEADACHE AND FEVER",
            glosses = listOf("HEADACHE", "FEVER"),
            referenceTranslations = listOf("I have a headache and fever"),
            expectedEmotion = "Sad"
        )

        composer.addToken("headache", t0CaptureMs = 998877L)
        testScheduler.advanceTimeBy(50)
        composer.addToken("fever", t0CaptureMs = 998899L)
        testScheduler.advanceTimeBy(50)

        composer.triggerComposition(
            fallbackTokens = listOf("headache", "fever"),
            t0CaptureMs = 998899L,
            detectedEmotion = "sad",
            targetPrompt = testPrompt
        )
        testScheduler.advanceTimeBy(100)

        val result = composedSentence
        assertNotNull(result)
        assertEquals(998899L, result?.t0CaptureMs)
        assertEquals(testPrompt, result?.targetPrompt)
        assertEquals(SentenceTone.CONCERNED, result?.tone)
        assertTrue("Latency should be recorded", (result?.latencyMs ?: 0L) >= 0L)
        composer.close()
    }

    // ── E. Emotion and Facial Linguistic Markers Synergy Tests ───────────────

    @Test
    fun testFacialMarkerQuestionSingleConceptEnglish() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("consult"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH,
            markers = LinguisticMarkers(isQuestion = true)
        )
        assertEquals("May I consult a doctor?", result.text)
        assertTrue(result.linguisticMarkers.isQuestion)
    }

    @Test
    fun testFacialMarkerQuestionMultiConceptEnglish() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("headache", "fever", "medicines"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH,
            markers = LinguisticMarkers(isQuestion = true)
        )
        assertTrue("Multi-token sentence with question marker must end with ?", result.text.endsWith("?"))
        assertTrue(result.linguisticMarkers.isQuestion)
    }

    @Test
    fun testFacialMarkerNegationSingleConceptEnglish() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("fever"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH,
            markers = LinguisticMarkers(isNegation = true)
        )
        assertEquals("I have no fever.", result.text)
        assertTrue(result.linguisticMarkers.isNegation)
    }

    @Test
    fun testFacialMarkerQuestionSingleConceptFilipino() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("consult"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO,
            markers = LinguisticMarkers(isQuestion = true)
        )
        assertEquals("Maaari ba akong kumonsulta sa doktor?", result.text)
        assertTrue(result.linguisticMarkers.isQuestion)
    }

    @Test
    fun testFacialMarkerNegationSingleConceptFilipino() = runTest {
        val result = synthesizer.compose(
            tokens = listOf("fever"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO,
            markers = LinguisticMarkers(isNegation = true)
        )
        assertEquals("Wala akong lagnat.", result.text)
        assertTrue(result.linguisticMarkers.isNegation)
    }

    @Test
    fun testComposerPassesLinguisticMarkersExplicitly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var composedSentence: SentenceResult? = null
        val composer = SentenceComposer(
            synthesizer = synthesizer,
            scope = testScope,
            dispatcher = testDispatcher,
            pauseDurationMs = 2000L,
            dedupeWindowMs = 0L,
            autoComposeEnabled = false
        )
        composer.onSentenceComposed = { result -> composedSentence = result }

        composer.triggerComposition(
            fallbackTokens = listOf("consult"),
            t0CaptureMs = 12345L,
            detectedEmotion = "sad",
            linguisticMarkers = LinguisticMarkers(isQuestion = true)
        )
        testScheduler.advanceTimeBy(100)

        val result = composedSentence
        assertNotNull(result)
        assertEquals(SentenceTone.CONCERNED, result?.tone)
        assertTrue(result?.linguisticMarkers?.isQuestion == true)
        assertEquals("May I consult a doctor?", result?.text)
        composer.close()
    }

    // ── G. Emotion & Tone Synergy Differentiation Tests ─────────────────────────

    @Test
    fun testEmotionAffectsPunctuationOnEmergencyEnglish() = runTest {
        val neutralResult = synthesizer.compose(
            tokens = listOf("accident"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val concernedResult = synthesizer.compose(
            tokens = listOf("accident"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("There has been an accident.", neutralResult.text)
        assertEquals("There has been an accident!", concernedResult.text)
        assertNotEquals(neutralResult.text, concernedResult.text)
    }

    @Test
    fun testEmotionAffectsPunctuationOnDistressEnglish() = runTest {
        val neutralHurt = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val concernedHurt = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I am hurt.", neutralHurt.text)
        assertEquals("I am hurt!", concernedHurt.text)
        assertNotEquals(neutralHurt.text, concernedHurt.text)

        val neutralSick = synthesizer.compose(
            tokens = listOf("sick"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val concernedSick = synthesizer.compose(
            tokens = listOf("sick"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("I am sick.", neutralSick.text)
        assertEquals("I am sick!", concernedSick.text)
        assertNotEquals(neutralSick.text, concernedSick.text)
    }

    @Test
    fun testEmotionAffectsGreetingEnglish() = runTest {
        val neutralHello = synthesizer.compose(
            tokens = listOf("hello"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val positiveHello = synthesizer.compose(
            tokens = listOf("hello"),
            tone = SentenceTone.POSITIVE,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("Hello.", neutralHello.text)
        assertEquals("Hello!", positiveHello.text)
        assertNotEquals(neutralHello.text, positiveHello.text)
    }

    @Test
    fun testEmotionAffectsMultiTokenEmergencyEnglish() = runTest {
        val neutralMulti = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val concernedMulti = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        assertEquals("There has been an accident. This is an emergency, please call an ambulance.", neutralMulti.text)
        assertEquals("There has been an accident. This is an emergency, please call an ambulance!", concernedMulti.text)
        assertNotEquals(neutralMulti.text, concernedMulti.text)
    }

    @Test
    fun testEmotionAffectsPunctuationFilipino() = runTest {
        val neutralAccident = synthesizer.compose(
            tokens = listOf("accident"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        val concernedAccident = synthesizer.compose(
            tokens = listOf("accident"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("May aksidente.", neutralAccident.text)
        assertEquals("May aksidente!", concernedAccident.text)
        assertNotEquals(neutralAccident.text, concernedAccident.text)

        val neutralHurt = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        val concernedHurt = synthesizer.compose(
            tokens = listOf("hurt"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Nasasaktan ako.", neutralHurt.text)
        assertEquals("Nasasaktan ako!", concernedHurt.text)
        assertNotEquals(neutralHurt.text, concernedHurt.text)

        val neutralHello = synthesizer.compose(
            tokens = listOf("hello"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        val positiveHello = synthesizer.compose(
            tokens = listOf("hello"),
            tone = SentenceTone.POSITIVE,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("Kamusta.", neutralHello.text)
        assertEquals("Kamusta!", positiveHello.text)
        assertNotEquals(neutralHello.text, positiveHello.text)

        val neutralMulti = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        val concernedMulti = synthesizer.compose(
            tokens = listOf("accident", "ambulance", "emergency"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("May aksidente. Emerhensya ito, tumawag ng ambulansya.", neutralMulti.text)
        assertEquals("May aksidente. Emerhensya ito, tumawag ng ambulansya!", concernedMulti.text)
        assertNotEquals(neutralMulti.text, concernedMulti.text)
    }

    // ── H. Exact user scenario: [accident, hurt, broken_bone] ────────────────

    @Test
    fun testEmotionAffectsAccidentHurtBrokenBoneEnglish() = runTest {
        val neutralResult = synthesizer.compose(
            tokens = listOf("accident", "hurt", "broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.ENGLISH
        )
        val concernedResult = synthesizer.compose(
            tokens = listOf("accident", "hurt", "broken_bone"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.ENGLISH
        )
        // Neutral: calm separate declarative sentences
        assertEquals("There has been an accident. I am hurt and have a broken bone.", neutralResult.text)
        // Concerned: dynamically restructured into urgent compound call for aid
        assertEquals("Please help! There has been an accident, I am hurt and have a broken bone!", concernedResult.text)
        assertNotEquals(neutralResult.text, concernedResult.text)
    }

    @Test
    fun testEmotionAffectsAccidentHurtBrokenBoneFilipino() = runTest {
        val neutralResult = synthesizer.compose(
            tokens = listOf("accident", "hurt", "broken_bone"),
            tone = SentenceTone.NEUTRAL,
            language = SentenceLanguage.FILIPINO
        )
        val concernedResult = synthesizer.compose(
            tokens = listOf("accident", "hurt", "broken_bone"),
            tone = SentenceTone.CONCERNED,
            language = SentenceLanguage.FILIPINO
        )
        assertEquals("May aksidente. Nasasaktan ako at may bali sa buto.", neutralResult.text)
        assertEquals("Saklolo! May aksidente, nasasaktan ako at may bali sa buto!", concernedResult.text)
        assertNotEquals(neutralResult.text, concernedResult.text)
    }

    // ── I. ToneMapper routing ────────────────────────────────────────────────

    @Test
    fun testToneMapperAngryMapsToConcerned() {
        assertEquals("concerned", ToneMapper.fromEmotion("angry"))
    }

    @Test
    fun testToneMapperSadMapsToConcerned() {
        assertEquals("concerned", ToneMapper.fromEmotion("sad"))
    }

    @Test
    fun testToneMapperDisgustMapsToConcerned() {
        assertEquals("concerned", ToneMapper.fromEmotion("disgust"))
    }

    @Test
    fun testToneMapperHappyMapsToPositive() {
        assertEquals("positive", ToneMapper.fromEmotion("happy"))
    }

    @Test
    fun testToneMapperNeutralMapsToNeutral() {
        assertEquals("neutral", ToneMapper.fromEmotion("neutral"))
    }

    @Test
    fun testToneMapperLowConfidenceFallsBackToNeutral() {
        // Confidence below threshold → always neutral regardless of emotion
        assertEquals("neutral", ToneMapper.fromEmotion("angry", confidence = 0.3f))
        assertEquals("neutral", ToneMapper.fromEmotion("sad", confidence = 0.49f))
    }
}
