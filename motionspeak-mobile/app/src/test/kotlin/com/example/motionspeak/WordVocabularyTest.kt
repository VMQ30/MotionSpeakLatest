package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordVocabularyTest {

    private fun word(id: Int, label: String, category: String = "Greeting", vocabulary: String? = null) =
        WordBankWord(id = id, label = label, category = category, vocabulary = vocabulary)

    // ── effectiveVocabulary ───────────────────────────────────────────────────

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
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(2, "MONDAY")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(3, "m")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(4, "NG")))
    }

    @Test
    fun unknownBackendValueFallsBackToTheRule() {
        assertEquals(VOCAB_LETTERS, effectiveVocabulary(word(1, "B", vocabulary = "numbers")))
        assertEquals(VOCAB_WORDS, effectiveVocabulary(word(2, "HELLO", vocabulary = "")))
    }

    // ── translatorStartVocabulary ─────────────────────────────────────────────

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
        assertNull(translatorStartVocabulary("shapes", hasLetters = true))
    }

    // ── gridCategories ────────────────────────────────────────────────────────

    private val bank = listOf(
        word(1, "HELLO", "Greetings"),
        word(2, "GOOD MORNING", "greetings"),
        word(3, "A", "Alphabet"),
        word(4, "B", "Alphabet"),
        word(5, "FEVER", "Medical"),
    )

    @Test
    fun allKeepsEveryCategoryInOrderWithTotals() {
        assertEquals(
            listOf(GridCategory("Medical", 1), GridCategory("Greetings", 2),
                   GridCategory("Alphabet", 2), GridCategory("Colors", 0)),
            gridCategories(bank, listOf("Medical", "Greetings", "Alphabet", "Colors"), VocabularyFilter.ALL),
        )
    }

    // Review Focus 1 (grid side).
    @Test
    fun gridCategoriesFilterByVocabulary() {
        val names = listOf("Medical", "Greetings", "Alphabet", "Colors")
        assertEquals(
            listOf(GridCategory("Medical", 1)),
            gridCategories(bank, names, VocabularyFilter.MEDICAL),
        )
        assertEquals(
            listOf(GridCategory("Greetings", 2)),
            gridCategories(bank, names, VocabularyFilter.CONVERSATIONAL),
        )
    }

    @Test
    fun mixedCategoryCountsOnlyMatchingWords() {
        val mixed = listOf(word(1, "A", "Greetings"), word(2, "HELLO", "Greetings"), word(3, "FEVER", "Medical"))
        assertEquals(listOf(GridCategory("Greetings", 1)), gridCategories(mixed, listOf("Greetings"), VocabularyFilter.CONVERSATIONAL))
        assertEquals(listOf(GridCategory("Medical", 1)), gridCategories(mixed, listOf("Medical"), VocabularyFilter.MEDICAL))
    }
}
