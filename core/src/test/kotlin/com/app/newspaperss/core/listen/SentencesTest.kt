package com.app.newspaperss.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {
    @Test
    fun splitsAtTheEndOfEachSentence() {
        assertEquals(
            listOf("It rained.", "Did it stop?", "No!", "\"Never,\" she said.", "(It did.)", "Then… sun."),
            Sentences.split("It rained. Did it stop? No! \"Never,\" she said. (It did.) Then… sun."),
        )
    }

    @Test
    fun aQuoteThatEndsASentenceStaysWithIt() {
        assertEquals(listOf("She said, “It's over.”", "Then she left."), Sentences.split("She said, “It's over.” Then she left."))
    }

    @Test
    fun titlesInitialsAndAbbreviationsDontEndASentence() {
        assertEquals(listOf("Mr. Smith met Dr. Jones at 3 p.m. today."), Sentences.split("Mr. Smith met Dr. Jones at 3 p.m. today."))
        assertEquals(listOf("J. R. R. Tolkien wrote it."), Sentences.split("J. R. R. Tolkien wrote it."))
        assertEquals(listOf("Apples, pears, etc. are fruit.", "So are plums."), Sentences.split("Apples, pears, etc. are fruit. So are plums."))
        assertEquals(listOf("Use a tool, e.g. a hammer."), Sentences.split("Use a tool, e.g. a hammer."))
        assertEquals(listOf("It costs 3.5 million.", "That's a lot."), Sentences.split("It costs 3.5 million. That's a lot."))
    }

    @Test
    fun noIsAWordUnlessANumberFollows() {
        assertEquals(listOf("I said no.", "Then I left."), Sentences.split("I said no. Then I left."))
        assertEquals(listOf("It was No. 5 on the list."), Sentences.split("It was No. 5 on the list."))
    }

    @Test
    fun chineseJapaneseAndHindiSentencesEndAtTheirOwnFullStops() {
        assertEquals(listOf("今日は晴れです。", "明日は雨です。"), Sentences.split("今日は晴れです。明日は雨です。"))
        assertEquals(listOf("「行こう！」", "彼は言った。"), Sentences.split("「行こう！」彼は言った。"))
        assertEquals(listOf("यह एक वाक्य है।", "यह दूसरा है।"), Sentences.split("यह एक वाक्य है। यह दूसरा है।"))
    }

    @Test
    fun aSentenceStartingWithANumberIsStillASentence() {
        assertEquals(listOf("It was late.", "2024 was worse."), Sentences.split("It was late. 2024 was worse."))
    }

    @Test
    fun spacesAreTidied() {
        assertEquals(listOf("One line.", "Two."), Sentences.split("  One\n line.\n\n Two.  "))
        assertEquals(emptyList<String>(), Sentences.split(" \n "))
    }

    @Test
    fun aVeryLongSentenceIsCutWhereItCanPause() {
        val long = (1..400).joinToString(", ") { "item $it" } + "."
        val parts = Sentences.split(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= Sentences.MAX_LENGTH })
        assertTrue(parts.dropLast(1).all { it.endsWith(",") })
        assertEquals(long, parts.joinToString(" "))
        // With nowhere to pause, it's cut by length, but never through a character.
        val emoji = "😀".repeat(Sentences.MAX_LENGTH)
        assertTrue(Sentences.split(emoji).all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
}
