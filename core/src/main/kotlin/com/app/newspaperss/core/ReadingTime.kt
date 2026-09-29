package com.app.newspaperss.core

object ReadingTime {
    const val DEFAULT_WPM = 238

    /** Minutes to read [wordCount] words, as a fraction so short pieces still count. */
    fun minutes(wordCount: Int, wpm: Int = DEFAULT_WPM): Double = wordCount.toDouble() / wpm
}
