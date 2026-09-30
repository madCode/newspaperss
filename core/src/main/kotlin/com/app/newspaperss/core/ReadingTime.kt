package com.app.newspaperss.core

import kotlin.math.roundToLong

object ReadingTime {
    const val DEFAULT_WPM = 238

    /**
     * How many words [text] is worth for reading time. Chinese and Japanese aren't written with
     * spaces, so a whole paragraph would count as one word: there, each character counts as the
     * part of a word it takes to read. Adults read them at about 350 characters a minute against
     * 238 English words (Trauzettel-Klosinski et al., 2012), so a character is about 2/3 of a word.
     */
    fun words(text: String): Int {
        var words = 0.0
        for (token in WHITESPACE.split(text)) {
            if (token.isEmpty()) continue
            var ideographs = 0
            var other = false
            var i = 0
            while (i < token.length) {
                val cp = token.codePointAt(i)
                if (Character.UnicodeScript.of(cp) in IDEOGRAPHIC) ideographs++ else if (Character.isLetterOrDigit(cp)) other = true
                i += Character.charCount(cp)
            }
            words += ideographs * WORDS_PER_IDEOGRAPH + if (other || ideographs == 0) 1 else 0
        }
        return words.roundToLong().toInt()
    }

    /** Minutes to read [wordCount] words, as a fraction so short pieces still count. */
    fun minutes(wordCount: Int, wpm: Int = DEFAULT_WPM): Double = wordCount.toDouble() / wpm

    /**
     * [minutes] as the reader sees them: "25 min", "1 hr", "1 hr 15 min". Rounded, but never
     * "0 min" for something that takes any time to read.
     */
    fun format(minutes: Double): String {
        val whole = if (minutes <= 0.0) 0L else maxOf(1L, minutes.roundToLong())
        if (whole < 60) return "$whole min"
        val rest = whole % 60
        return if (rest == 0L) "${whole / 60} hr" else "${whole / 60} hr $rest min"
    }

    private val WHITESPACE = Regex("\\s+")
    private val IDEOGRAPHIC = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    private const val WORDS_PER_IDEOGRAPH = 238.0 / 350.0
}
