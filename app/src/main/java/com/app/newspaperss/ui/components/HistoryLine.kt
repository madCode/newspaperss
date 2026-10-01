package com.app.newspaperss.ui.components

import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleHistory
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.FeedSync
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The few words under an article's title saying what has happened to it: waiting (and for how
 * long more, if it [expires]), in the next edition, in an unsent edition, sent when, read, or not
 * picked. [history] names the day it went out or the edition holding it, where known.
 */
fun historyLine(
    article: ArticleEntity,
    history: ArticleHistory?,
    expires: Boolean,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (article.starredAt != null && article.state != ArticleState.IN_EDITION) return "In your next edition"
    return when (article.state) {
        ArticleState.NEW -> if (expires) "Waiting, ${timeLeft(article.discoveredAt, now)}" else "Waiting"
        ArticleState.IN_EDITION -> editionDay(history?.editionTitle)?.let { "In $it's edition" } ?: "In an unsent edition"
        ArticleState.DELIVERED -> history?.sentAt?.let { "Sent ${dayOf(it, now, zone, locale)}" } ?: "Sent"
        ArticleState.SKIPPED -> "Read"
        // Not "too old": a list's newest-only cap and leaving a feed out expire articles too.
        ArticleState.EXPIRED -> "Not picked"
    }
}

/** Counted in whole days, rounding up, so the last day reads as such until the next sync expires it. */
private fun timeLeft(discoveredAt: Instant, now: Instant): String {
    val left = Duration.between(now, discoveredAt.plus(FeedSync.KEEP_WAITING))
    val days = (left.toHours() + 23) / 24
    return if (days <= 1) "last day" else "$days days left"
}

/** An edition's day, from its title ("Thursday Morning Edition, Oct 2"); titles are always English. */
private fun editionDay(title: String?): String? =
    title?.substringBefore(' ')?.takeIf { word -> DayOfWeek.entries.any { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) == word } }

/** "today", "yesterday", a weekday within the last week, else a short date. */
private fun dayOf(at: Instant, now: Instant, zone: ZoneId, locale: Locale): String {
    val date = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        date == today -> "today"
        date == today.minusDays(1) -> "yesterday"
        date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> DateTimeFormatter.ofPattern("MMM d", locale).format(date)
    }
}
