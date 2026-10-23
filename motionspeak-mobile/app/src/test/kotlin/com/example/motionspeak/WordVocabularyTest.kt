package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordVocabularyTest {

    private fun word(id: Int, label: String, category: String = CATEGORY_GENERAL, vocabulary: String? = null) =
        WordBankWord(id = id, label = label, category = category, vocabulary = vocabulary)

    // ── effectiveVocabulary ─────────────────────────────────────────────────────────────

    @Test
    fun usesTheBackendValueWhenPresent() {
        assertEquals(VOCAB_LETTERS, effectiveVocabulary(word(1, "NG", vocabulary = "letters")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(2, "A", vocabulary = "words")))
        assertEquals(VOCAB_LETTERS, effectiveVocabulary(word(3, "M", vocabulary = "LETTERS")))
    }

    // Review Focus 1: word banks cached before the backend sent the field.
    @Test
    fun fallsBackToSingleCapitalLetterRule() {
        assertEquals(VOCAB_LETTERS, effectiveVocabulary(word(1, "M")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(2, "FEVER")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(3, "m")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(4, "NG")))
    }

    @Test
    fun unknownBackendValueFallsBackToTheRule() {
        assertEquals(VOCAB_LETTERS, effectiveVocabulary(word(1, "B", vocabulary = "emergency")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(2, "HELLO", vocabulary = "")))
    }

    // ── translatorStartVocabulary ───────────────────────────────────────────────────────

    @Test
    fun lettersRequestOpensOnLetters() {
        assertEquals(PredictionService.Vocabulary.LETTERS, translatorStartVocabulary("letters", hasLetters = true))
    }

    // Review Focus 2.
    @Test
    fun lettersRequestIgnoredWithoutLettersModel() {
        assertNull(translatorStartVocabulary("letters", hasLetters = false))
    }

    @Test
    fun wordsNullAndUnknownRequestsChangeNothing() {
        assertNull(translatorStartVocabulary("words", hasLetters = true))
        assertNull(translatorStartVocabulary(null, hasLetters = true))
        assertNull(translatorStartVocabulary("emergency", hasLetters = true))
    }

    // ── gridCategories ─────────────────────────────────────────────────────────────────

    private val bank = listOf(
        word(1, "hello", CATEGORY_GENERAL),
        word(2, "good_morning", CATEGORY_GENERAL),
        word(3, "asthma", CATEGORY_MEDICAL),
        word(4, "cough", CATEGORY_MEDICAL),
        word(5, "fever", CATEGORY_MEDICAL),
    )

    @Test
    fun allKeepsEveryCategoryInOrderWithTotals() {
        assertEquals(
            listOf(GridCategory(CATEGORY_MEDICAL, 3), GridCategory(CATEGORY_GENERAL, 2)),
            gridCategories(bank, listOf(CATEGORY_MEDICAL, CATEGORY_GENERAL), VocabularyFilter.ALL),
        )
    }

    @Test
    fun gridCategoriesFilterByVocabulary() {
        val names = listOf(CATEGORY_MEDICAL, CATEGORY_GENERAL)
        assertEquals(
            listOf(GridCategory(CATEGORY_MEDICAL, 3)),
            gridCategories(bank, names, VocabularyFilter.MEDICAL),
        )
        assertEquals(
            listOf(GridCategory(CATEGORY_GENERAL, 2)),
            gridCategories(bank, names, VocabularyFilter.GENERAL),
        )
    }

    @Test
    fun mixedCategoryCountsOnlyMatchingWords() {
        val mixed = listOf(
            word(1, "hello", CATEGORY_GENERAL),
            word(2, "hi", CATEGORY_GENERAL),
            word(3, "fever", CATEGORY_MEDICAL)
        )
        assertEquals(listOf(GridCategory(CATEGORY_GENERAL, 2)), gridCategories(mixed, listOf(CATEGORY_GENERAL), VocabularyFilter.GENERAL))
        assertEquals(listOf(GridCategory(CATEGORY_MEDICAL, 1)), gridCategories(mixed, listOf(CATEGORY_MEDICAL), VocabularyFilter.MEDICAL))
    }

    // ── defaultWordBank & filterAndNormalizeToLabels ────────────────────────────────────

    @Test
    fun defaultWordBankHasExactly60Signs() {
        val defaultWords = defaultWordBank()
        assertEquals(60, defaultWords.size)
        assertEquals(46, defaultWords.count { it.category == CATEGORY_MEDICAL })
        assertEquals(14, defaultWords.count { it.category == CATEGORY_GENERAL })
    }

    @Test
    fun defaultCategoriesHasOnlyMedicalAndGeneral() {
        val cats = defaultCategories()
        assertEquals(2, cats.size)
        assertEquals(CATEGORY_MEDICAL, cats[0].name)
        assertEquals(46, cats[0].word_count)
        assertEquals(CATEGORY_GENERAL, cats[1].name)
        assertEquals(14, cats[1].word_count)
    }

    @Test
    fun filterAndNormalizeDiscardsUnknownAndFillsMissing() {
        val incoming = listOf(
            WordBankWord(id = 999, label = "unknown_sign_not_in_labels", category = "Random"),
            WordBankWord(id = 500, label = "fever", category = "Symptoms", filipino_translation = "Mataas na lagnat")
        )
        val normalized = filterAndNormalizeToLabels(incoming)
        assertEquals(60, normalized.size)
        assertTrue(normalized.none { it.label == "unknown_sign_not_in_labels" })

        val fever = normalized.first { it.label == "fever" }
        assertEquals(CATEGORY_MEDICAL, fever.category)
        assertEquals("Mataas na lagnat", fever.filipino_translation)
    }
}
