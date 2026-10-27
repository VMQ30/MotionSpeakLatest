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

    // ── 2. SHA-256 and Integrity Verification Tests ──────────────────────────

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

    // ── 3. LlmSynthesizer Fallback Behavior ───────────────────────────────────

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
