package com.app.newspaperss.core.feed

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/** Parses the date formats found in real feeds; null when none fits. */
internal object FeedDates {
    // RFC 822 as feeds actually write it: optional weekday, 2- or 4-digit
    // years, and zones as offsets or names (GMT, EST, Z).
    private val rfc822 = listOf(
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm zzz",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss Z",
    ).map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.US) }

    private val usZones = mapOf(
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "UT" to "+0000", "Z" to "+0000",
    )

    // RFC_1123 would read "29 Sep 26" as the year 26.
    private val twoDigitYear = Regex("(\\d{1,2} [A-Za-z]{3}) (\\d{2}) ")

    fun parse(raw: String?): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return iso(text) ?: rfc(text)
    }

    private fun iso(text: String): Instant? {
        try { return OffsetDateTime.parse(text).toInstant() } catch (_: DateTimeParseException) {}
        try { return Instant.parse(text) } catch (_: DateTimeParseException) {}
        // A date-time without a zone, or a bare date: assume UTC.
        try { return java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) } catch (_: DateTimeParseException) {}
        try { return java.time.LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) } catch (_: DateTimeParseException) {}
        return null
    }

    private fun rfc(text: String): Instant? {
        val normalized = text.replace(Regex("\\s+"), " ").let { t ->
            val zone = t.substringAfterLast(' ')
            usZones[zone.uppercase()]?.let { t.dropLast(zone.length) + it } ?: t
        }.replace(twoDigitYear, "$1 20$2 ")
        try { return ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } catch (_: DateTimeParseException) {}
        for (f in rfc822) {
            try { return ZonedDateTime.parse(normalized, f).toInstant() } catch (_: DateTimeParseException) {}
        }
        return null
    }
}
