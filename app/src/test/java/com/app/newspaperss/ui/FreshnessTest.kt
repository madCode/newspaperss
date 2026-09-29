package com.app.newspaperss.ui

import com.app.newspaperss.ui.sources.freshness
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FreshnessTest {
    private val now = Instant.parse("2026-09-29T10:00:00Z")

    @Test
    fun countsCalendarDaysNotHours() {
        assertEquals("New articles today", freshness(Instant.parse("2026-09-29T00:10:00Z"), now, ZoneOffset.UTC))
        assertEquals("Last new article yesterday", freshness(Instant.parse("2026-09-28T23:50:00Z"), now, ZoneOffset.UTC))
        assertEquals("Last new article 12 days ago", freshness(Instant.parse("2026-09-17T12:00:00Z"), now, ZoneOffset.UTC))
        assertEquals("No articles yet", freshness(null, now, ZoneOffset.UTC))
    }
}
