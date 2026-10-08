package com.app.newspaperss.core.listen

/**
 * What looks wrong in the audio Kokoro made of a line, for the podcast log: a gap of silence
 * inside it, a long one after it, audio too short for its text, or samples that aren't numbers.
 * Speech Kokoro makes properly pauses at most about 0.2 s inside a line and says 15 to 25
 * characters a second, so the limits here sit well clear of it.
 */
object SpeechCheck {
    /** Below this, a 10 ms window counts as silence: Kokoro's own silence is near zero. */
    private const val QUIET = 0.005f
    private const val QUIET_GAP_SECONDS = 0.5
    private const val QUIET_END_SECONDS = 0.5
    private const val MAX_CHARACTERS_A_SECOND = 30.0

    /** Text this short says too little to judge the pace by. */
    private const val PACE_FROM_CHARACTERS = 40

    /** What's odd about [samples], made from text [characters] long, or null if nothing is. */
    fun problems(samples: FloatArray, rate: Int, characters: Int): String? {
        val window = maxOf(1, rate / 100)
        val windows = (samples.size + window - 1) / window
        // NaN would pass for silence: the encoder turns it into zeros.
        var broken = 0
        val loud = BooleanArray(windows)
        for (w in 0 until windows) {
            for (i in w * window until minOf(samples.size, (w + 1) * window)) {
                val s = samples[i]
                if (!s.isFinite()) broken++ else if (s > QUIET || s < -QUIET) loud[w] = true
            }
        }
        val found = mutableListOf<String>()
        if (broken > 0) found += "$broken samples not numbers"
        val first = loud.indexOfFirst { it }
        if (first < 0) {
            if (characters > 0) found += "no sound in ${seconds(samples.size, rate)}s"
            return found.joinToString("; ").ifEmpty { null }
        }
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
            found += "${round(longest * step)}s silent from ${round((longestEnd - longest + 1) * step)}s of ${seconds(samples.size, rate)}s"
        }
        val after = (windows - 1 - last) * step
        if (after >= QUIET_END_SECONDS) found += "${round(after)}s silent at the end"
        val pace = characters / ((last - first + 1) * step)
        if (characters >= PACE_FROM_CHARACTERS && pace > MAX_CHARACTERS_A_SECOND) {
            found += "${round((last - first + 1) * step)}s of sound for $characters characters"
        }
        return found.joinToString("; ").ifEmpty { null }
    }

    private fun seconds(samples: Int, rate: Int) = round(samples.toDouble() / rate)

    private fun round(seconds: Double) = Math.round(seconds * 100) / 100.0
}
