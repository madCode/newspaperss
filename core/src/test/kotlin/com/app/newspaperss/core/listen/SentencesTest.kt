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
    }
}
