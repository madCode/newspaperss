package com.app.newspaperss.core.edition

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class EditionTitlesTest {
    private val tuesdayMorning = LocalDateTime.of(2026, 9, 29, 6, 30)

    @Test
    fun partOfDay() {
        assertEquals("Tuesday Morning Edition, Sep 29", EditionTitles.title(tuesdayMorning, emptyList(), Locale.US))
        assertEquals("Tuesday Afternoon Edition, Sep 29", EditionTitles.title(tuesdayMorning.withHour(13), emptyList(), Locale.US))
        assertEquals("Tuesday Evening Edition, Sep 29", EditionTitles.title(tuesdayMorning.withHour(2), emptyList(), Locale.US))
    }

    @Test
    fun nextWeeksEditionHasItsOwnTitle() {
        val lastWeek = EditionTitles.title(tuesdayMorning, emptyList(), Locale.US)
        assertEquals("Tuesday Morning Edition, Oct 6", EditionTitles.title(tuesdayMorning.plusWeeks(1), listOf(lastWeek), Locale.US))
    }

    @Test
    fun theTitleIsEnglishLikeTheRestOfTheBookWhateverThePhonesLanguage() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("Tuesday Morning Edition, Sep 29", EditionTitles.title(tuesdayMorning, emptyList()))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun repeatsGetANumber() {
        val existing = listOf("Tuesday Morning Edition, Sep 29", "Tuesday Morning Edition, Sep 29 (2)")
        assertEquals("Tuesday Morning Edition, Sep 29 (3)", EditionTitles.title(tuesdayMorning, existing, Locale.US))
    }
}
