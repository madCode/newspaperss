package com.app.newspaperss.core.edition

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object EditionTitles {
    /**
     * "Tuesday Morning Edition, Sep 29", made unique against [existing] with " (2)",
     * " (3)": Send to Kindle silently drops a document whose title it has already
     * received. The date keeps next week's Tuesday from repeating this one, in
     * e-reader libraries and synced folders too.
     */
    fun title(at: LocalDateTime, existing: Collection<String>, locale: Locale = Locale.getDefault()): String {
        val part = when (at.hour) {
            in 4..11 -> "Morning"
            in 12..16 -> "Afternoon"
            else -> "Evening"
        }
        val day = at.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val date = at.format(DateTimeFormatter.ofPattern("MMM d", locale))
        val base = "$day $part Edition, $date"
        if (base !in existing) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in existing }
    }
}
