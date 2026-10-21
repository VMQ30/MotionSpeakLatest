package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HomeContentTest {

    private val manila: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(manila).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun entry(ts: Long, word: String = "HELLO") =
        TranslationEntry(word = word, confidence = 90, gestureType = "motion", timestamp = ts)

    private fun word(id: Int, category: String) = WordBankWord(id = id, label = "W$id", category = category)

    // ── Greeting ──────────────────────────────────────────────────────────────

    @Test
    fun timeOfDayBoundaries() {
        // 5:00 PM - 3:59 AM (Evening)
        assertEquals("Good evening 🌙", homeGreeting(0, null).title)
        assertEquals("Good evening 🌙", homeGreeting(3, null).title)
        // 4:00 AM - 11:59 AM (Morning)
        assertEquals("Good morning ☀️", homeGreeting(4, null).title)
        assertEquals("Good morning ☀️", homeGreeting(11, null).title)
        // 12:00 PM - 4:59 PM (Afternoon)
        assertEquals("Good afternoon 🌤️", homeGreeting(12, null).title)
        assertEquals("Good afternoon 🌤️", homeGreeting(16, null).title)
        // 5:00 PM - 3:59 AM (Evening)
        assertEquals("Good evening 🌙", homeGreeting(17, null).title)
        assertEquals("Good evening 🌙", homeGreeting(18, null).title)
        assertEquals("Good evening 🌙", homeGreeting(23, null).title)
    }

    @Test
    fun dynamicRtcGreetingFollowsCalendar() {
        val calMorning = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8) }
        val calAfternoon = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 14) }
        val calEvening = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 20) }
        val calLateNight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 1) }

        assertEquals("Good morning ☀️", dynamicRtcGreeting(null, calMorning).title)
        assertEquals("Good afternoon 🌤️", dynamicRtcGreeting(null, calAfternoon).title)
        assertEquals("Good evening 🌙", dynamicRtcGreeting(null, calEvening).title)
        assertEquals("Good evening 🌙", dynamicRtcGreeting(null, calLateNight).title)
    }

    @Test
    fun nameMovesTimeLineToSubtitle() {
        val g = homeGreeting(9, "  Maria ")
        assertEquals("Hi Maria!", g.title)
        assertEquals("Good morning ☀️", g.subtitle)
    }

    @Test
    fun noNameMeansNoSubtitle() {
        val g = homeGreeting(20, null)
        assertEquals("Good evening 🌙", g.title)
        assertNull(g.subtitle)
    }

    // Review Focus 4.
    @Test
    fun blankNameTreatedAsNoName() {
        val g = homeGreeting(14, "   ")
        assertEquals("Good afternoon 🌤️", g.title)
        assertNull(g.subtitle)
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    // HomeStats is just three independently-sourced numbers (word bank size,
    // TranslationHistoryManager.getTranslatedToday(), favorites count). The
    // daily counter resets by comparing localDayKey()s, tested below.

    @Test
    fun sameLocalDayUsesTheGivenTimeZone() {
        val a = at(2026, 9, 28, 23, 30)
        val b = at(2026, 9, 29, 0, 30)
        assertFalse(isSameLocalDay(a, b, manila))
        assertTrue(isSameLocalDay(a, at(2026, 9, 28, 0, 0), manila))
    }

    @Test
    fun localDayKeyChangesAtLocalMidnight() {
        val lateNight = at(2026, 9, 28, 23, 59)
        assertEquals(localDayKey(at(2026, 9, 28, 0, 0), manila), localDayKey(lateNight, manila))
        assertFalse(localDayKey(lateNight, manila) == localDayKey(at(2026, 9, 29, 0, 0), manila))
        // Same day-of-year in different years must not collide.
        assertFalse(localDayKey(at(2025, 9, 28, 12, 0), manila) == localDayKey(at(2026, 9, 28, 12, 0), manila))
    }

    // ── Categories ────────────────────────────────────────────────────────────

    @Test
    fun categoriesOrderedByCountThenNameAndLimited() {
        val words = listOf(
            word(1, "Greetings"), word(2, "greetings"), word(3, "GREETINGS"),
            word(4, "Family"),
            word(5, "Questions"), word(6, "Questions"),
            word(7, "Days"), word(8, "days"),
        )
        val names = listOf("Family", "Questions", "Greetings", "Days", "Colors")
        val result = homeCategories(words, names, limit = 3)
        assertEquals(
            listOf(HomeCategory("Greetings", 3), HomeCategory("Days", 2), HomeCategory("Questions", 2)),
            result,
        )
    }

    @Test
    fun categoriesWithNoWordsAreExcluded() {
        val result = homeCategories(listOf(word(1, "Family")), listOf("Family", "Colors"))
        assertEquals(listOf(HomeCategory("Family", 1)), result)
    }

    @Test
    fun categoriesFallBackToWordCategoriesWhenNamesMissing() {
        val words = listOf(word(1, "Days"), word(2, "Family"), word(3, "family"))
        assertEquals(
            listOf(HomeCategory("Family", 2), HomeCategory("Days", 1)),
            homeCategories(words, emptyList()),
        )
    }

    // Review Focus 2.
    @Test
    fun homeCategoriesEmptyWhenNoWords() {
        assertTrue(homeCategories(emptyList(), listOf("Family")).isEmpty())
        assertTrue(homeCategories(emptyList(), emptyList()).isEmpty())
    }

    // ── Recent ────────────────────────────────────────────────────────────────

    @Test
    fun recentTakesNewestThree() {
        val all = (5 downTo 1).map { entry(it.toLong(), "W$it") } // newest first, as stored
        assertEquals(listOf("W5", "W4", "W3"), recentEntries(all).map { it.word })
        assertEquals(1, recentEntries(all.take(1)).size)
    }
}
