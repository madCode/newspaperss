package com.app.newspaperss.core.edition

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class EditionTitlesTest {
    private val tuesdayMorning = LocalDateTime.of(2026, 9, 29, 6, 30)

    @Test
    fun partOfDay() {
        assertEquals("Tuesday Morning Edition", EditionTitles.title(tuesdayMorning, emptyList(), Locale.US))
        assertEquals("Tuesday Afternoon Edition", EditionTitles.title(tuesdayMorning.withHour(13), emptyList(), Locale.US))
        assertEquals("Tuesday Evening Edition", EditionTitles.title(tuesdayMorning.withHour(2), emptyList(), Locale.US))
    }

    @Test
    fun repeatsGetANumber() {
        val existing = listOf("Tuesday Morning Edition", "Tuesday Morning Edition (2)")
        assertEquals("Tuesday Morning Edition (3)", EditionTitles.title(tuesdayMorning, existing, Locale.US))
    }
}
