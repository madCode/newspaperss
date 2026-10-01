package com.app.newspaperss.ui.components

import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleHistory
import com.app.newspaperss.data.ArticleState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class HistoryLineTest {
    // A Thursday.
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private fun article(state: ArticleState, foundAgo: Duration = Duration.ofDays(1), starred: Boolean = false) = ArticleEntity(
        id = 1, sourceId = 1, guid = "g", url = "https://example.com/a", title = "A", state = state,
        discoveredAt = now.minus(foundAgo), starredAt = if (starred) now else null,
    )

    private fun line(article: ArticleEntity, history: ArticleHistory? = null, expires: Boolean = true) =
        historyLine(article, history, expires, now = now, zone = ZoneOffset.UTC, locale = Locale.ENGLISH)

    /** Days left count down the week a waiting article has, so the reader can star it before it goes. */
    @Test
    fun aWaitingArticleSaysHowLongItHasLeft() {
        assertEquals("Waiting, 6 days left", line(article(ArticleState.NEW, foundAgo = Duration.ofDays(1))))
        assertEquals("Waiting, 2 days left", line(article(ArticleState.NEW, foundAgo = Duration.ofDays(5).plusHours(1))))
        assertEquals("Waiting, last day", line(article(ArticleState.NEW, foundAgo = Duration.ofDays(6).plusHours(3))))
        assertEquals("overdue until the next sync expires it", "Waiting, last day", line(article(ArticleState.NEW, foundAgo = Duration.ofDays(8))))
        assertEquals("a saved link never expires", "Waiting", line(article(ArticleState.NEW), expires = false))
    }

    @Test
    fun aStarIsTheNextEditionWhateverElseHappened() {
        assertEquals("In your next edition", line(article(ArticleState.DELIVERED, starred = true)))
        assertEquals("In your next edition", line(article(ArticleState.NEW, starred = true)))
    }

    /** Editions leave paused sources out, so a star there mustn't promise the next one. */
    @Test
    fun aStarOnAPausedSourceWaits() {
        assertEquals("Starred, source paused", historyLine(article(ArticleState.NEW, starred = true), null, expires = true, paused = true, now = now))
    }

    @Test
    fun anArticleInAnUnsentEditionNamesItsDay() {
        val inEdition = article(ArticleState.IN_EDITION, starred = true)
        assertEquals("In Thursday's edition", line(inEdition, ArticleHistory(1, null, "Thursday Morning Edition, Oct 1")))
        assertEquals("In Friday's edition", line(inEdition, ArticleHistory(1, null, "Friday Morning Edition, Oct 2 (2)")))
        assertEquals("In an unsent edition", line(inEdition, ArticleHistory(1, null, "Special issue")))
        assertEquals("In an unsent edition", line(inEdition))
    }

    @Test
    fun aSentArticleSaysWhen() {
        fun sent(at: String) = line(article(ArticleState.DELIVERED), ArticleHistory(1, Instant.parse(at), null))
        assertEquals("Sent today", sent("2026-10-01T06:00:00Z"))
        assertEquals("Sent yesterday", sent("2026-09-30T20:00:00Z"))
        assertEquals("Sent Saturday", sent("2026-09-26T08:00:00Z"))
        assertEquals("Sent Sep 24", sent("2026-09-24T08:00:00Z"))
        assertEquals("when it went out is forgotten after a while", "Sent", line(article(ArticleState.DELIVERED)))
    }

    @Test
    fun readAndExpiredSayJustThat() {
        assertEquals("Read", line(article(ArticleState.SKIPPED)))
        assertEquals("Not picked", line(article(ArticleState.EXPIRED)))
    }

    /** A paid post its source skipped didn't wait too long; it says why it never went in. */
    @Test
    fun aSkippedPaidPostSaysSo() {
        assertEquals("Skipped: a paid post", line(article(ArticleState.EXPIRED).copy(paidOnly = true)))
        assertEquals("Not picked", line(article(ArticleState.EXPIRED)))
    }
}
