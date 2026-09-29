package com.app.newspaperss.core

import kotlin.math.roundToLong

object ReadingTime {
    const val DEFAULT_WPM = 238

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
}
