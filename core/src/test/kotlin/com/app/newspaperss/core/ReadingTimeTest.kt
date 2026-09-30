package com.app.newspaperss.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTimeTest {
    @Test
    fun shortPiecesCountTowardTheBudget() {
        assertEquals(0.5, ReadingTime.minutes(119, wpm = 238), 0.01)
    }

    @Test
    fun formattedMinutesAreRoundedButNeverZeroForARealArticle() {
        val cases = listOf(
            0.0 to "0 min",
            0.2 to "1 min",
            1.4 to "1 min",
            1.5 to "2 min",
            59.4 to "59 min",
            59.6 to "1 hr",
            75.0 to "1 hr 15 min",
            125.0 to "2 hr 5 min",
        )
        for ((minutes, expected) in cases) assertEquals("$minutes", expected, ReadingTime.format(minutes))
    }

    @Test
    fun pluralPicksTheNounForTheCount() {
        assertEquals("0 articles", plural(0, "article"))
        assertEquals("1 article", plural(1, "article"))
        assertEquals("2 articles", plural(2, "article"))
        assertEquals("3 stories", plural(3, "story", "stories"))
    }

    @Test
    fun englishCountsWordsBetweenSpaces() {
        assertEquals(9, ReadingTime.words("The council voted on Tuesday to extend the lanes."))
        // A token of punctuation, a number or an emoji still counts, as it always has.
        assertEquals(4, ReadingTime.words("Yes — 100% 🎉"))
    }

    @Test
    fun chineseAndJapaneseCountByCharacterSoAParagraphIsntOneWord() {
        // 350 characters take about as long as 238 English words.
        assertEquals(238, ReadingTime.words("市".repeat(350)))
        assertEquals(238, ReadingTime.words("あ".repeat(175) + "東".repeat(175)))
        // Mixed with Latin words and spaces: each part counts its own way.
        assertEquals(2 + 34, ReadingTime.words("Nintendo Switch " + "発".repeat(50)))
        // The long-vowel mark is part of a katakana word, not a word of its own.
        assertEquals(ReadingTime.words("ホムニュススポツ"), ReadingTime.words("ホームニュースス"))
    }

    @Test
    fun koreanUsesSpacesSoCountsByWord() {
        assertEquals(4, ReadingTime.words("시의회는 화요일 자전거 도로를"))
    }
}
