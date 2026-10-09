package com.app.newspaperss.core.listen

/**
 * What looks wrong in the audio Kokoro made of a line, for the podcast log: a quiet gap inside
 * it, a long one after it, audio too short for its text, or samples that aren't numbers. Quiet
 * is judged against the line's own loudness, so a stretch of faint noise where words should be
 * counts as well as true silence. Speech Kokoro makes properly drops that low for at most about
 * a quarter of a second inside a line, and says 15 to 25 characters a second, so the limits here
 * sit well clear of it.
 */
object SpeechCheck {
    /** A 20 ms window this far below the line's typical loudness counts as quiet. */
    private const val QUIET = 0.1

    /** Loudness (RMS) below which a window is no sound at all: Kokoro's own silence is near zero. */
    private const val SILENT = 0.01
    private const val QUIET_GAP_SECONDS = 0.5
    private const val QUIET_END_SECONDS = 0.5
    private const val MAX_CHARACTERS_A_SECOND = 30.0

    /** Text this short says too little to judge the pace by. */
    private const val PACE_FROM_CHARACTERS = 40

    /** What's odd about [samples], made from text [characters] long, or null if nothing is. */
    fun problems(samples: FloatArray, rate: Int, characters: Int): String? {
        val window = maxOf(1, rate / 50)
        val windows = (samples.size + window - 1) / window
        // NaN would pass for silence: the encoder turns it into zeros.
        var broken = 0
        val loudness = DoubleArray(windows) { w ->
            var sum = 0.0
            val end = minOf(samples.size, (w + 1) * window)
            for (i in w * window until end) {
                val s = samples[i]
                if (s.isFinite()) sum += s * s else broken++
            }
            Math.sqrt(sum / (end - w * window))
        }
        val found = mutableListOf<String>()
        if (broken > 0) found += "$broken samples not numbers"
        val sounding = loudness.filter { it >= SILENT }.sorted()
        if (sounding.isEmpty()) {
            if (characters > 0) found += "no sound in ${seconds(samples.size, rate)}s"
            return found.joinToString("; ").ifEmpty { null }
        }
        val quiet = sounding[sounding.size / 2] * QUIET
        val loud = BooleanArray(windows) { loudness[it] >= quiet }
        val first = loud.indexOfFirst { it }
        val last = loud.indexOfLast { it }
        var run = 0
        var longest = 0
        var longestEnd = 0
        for (w in first..last) {
            run = if (loud[w]) 0 else run + 1
            if (run > longest) {
                longest = run
                longestEnd = w
            }
        }
        val step = window.toDouble() / rate
        if (longest * step >= QUIET_GAP_SECONDS) {
            found += "${round(longest * step)}s quiet from ${round((longestEnd - longest + 1) * step)}s of ${seconds(samples.size, rate)}s"
        }
        val after = (windows - 1 - last) * step
        if (after >= QUIET_END_SECONDS) found += "${round(after)}s quiet at the end"
        val pace = characters / ((last - first + 1) * step)
        if (characters >= PACE_FROM_CHARACTERS && pace > MAX_CHARACTERS_A_SECOND) {
            found += "${round((last - first + 1) * step)}s of sound for $characters characters"
        }
        return found.joinToString("; ").ifEmpty { null }
    }

    private fun seconds(samples: Int, rate: Int) = round(samples.toDouble() / rate)

    private fun round(seconds: Double) = Math.round(seconds * 100) / 100.0
}
