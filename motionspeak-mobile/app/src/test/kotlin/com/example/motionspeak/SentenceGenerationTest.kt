package com.example.motionspeak

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
