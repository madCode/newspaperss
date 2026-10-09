package com.app.newspaperss.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class SpeechCheckTest {
    private val rate = 24_000

    /** A tone standing in for speech, with silence where [quiet] says, in seconds. */
    private fun audio(seconds: Double, vararg quiet: ClosedFloatingPointRange<Double>, faint: Double = 0.0) = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        val level = if (quiet.any { t in it }) faint else 0.3
        (level * sin(t * 2 * Math.PI * 220)).toFloat()
    }

    @Test
    fun speechWithShortPausesIsFine() {
        assertNull(SpeechCheck.problems(audio(3.0, 1.0..1.2, 2.9..3.0), rate, 50))
    }

    @Test
    fun aGapOfSilenceInsideTheLineIsReported() {
        assertEquals("1.5s quiet from 1.0s of 4.0s", SpeechCheck.problems(audio(4.0, 1.0..2.5), rate, 60))
    }

    @Test
    fun faintNoiseWhereWordsShouldBeIsReportedAsQuiet() {
        // Above any fixed floor for silence, but a twentieth of the speech around it.
        assertEquals("1.5s quiet from 1.0s of 4.0s", SpeechCheck.problems(audio(4.0, 1.0..2.5, faint = 0.015), rate, 60))
    }

    @Test
    fun silenceAtTheEndIsReported() {
        assertEquals("1.0s quiet at the end", SpeechCheck.problems(audio(3.0, 2.0..3.0), rate, 30))
    }

    @Test
    fun soundTooShortForTheTextIsReported() {
        assertEquals("1.0s of sound for 100 characters", SpeechCheck.problems(audio(1.0), rate, 100))
    }

    @Test
    fun samplesThatArentNumbersAreReported() {
        val samples = audio(2.0).also { it[100] = Float.NaN; it[200] = Float.POSITIVE_INFINITY }
        assertEquals("2 samples not numbers", SpeechCheck.problems(samples, rate, 20))
    }

    @Test
    fun aLineWithNoSoundIsReported() {
        assertEquals("no sound in 0.5s", SpeechCheck.problems(FloatArray(rate / 2), rate, 20))
        assertEquals("1 samples not numbers; no sound in 0.5s", SpeechCheck.problems(FloatArray(rate / 2).also { it[0] = Float.NaN }, rate, 20))
    }
}
