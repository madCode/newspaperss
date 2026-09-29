package com.app.newspaperss.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullTextCheckTest {
    private fun extracted(feedWords: Int, pageWords: Int?, usedFeed: Boolean, blocked: Boolean = false, note: String? = null) =
        ExtractedArticle(
            title = "t", author = null, html = "<p>t</p>", wordCount = if (usedFeed) feedWords else pageWords ?: 0, minutes = 1.0,
            usedFeedContent = usedFeed, note = note, imageUrls = emptyList(),
            feedWordCount = feedWords, pageWordCount = pageWords, pageBlocked = blocked,
        )

    @Test
    fun eachArticleOutcomeCountsAsTheRightEvidence() {
        assertEquals(FullTextEvidence.PAGE_LONGER, FullTextCheck.evidence(extracted(40, 900, usedFeed = false)))
        assertEquals("a long feed item taken without fetching", FullTextEvidence.FEED_FULL, FullTextCheck.evidence(extracted(800, null, usedFeed = true)))
        assertEquals("a long feed that beat the page", FullTextEvidence.FEED_FULL, FullTextCheck.evidence(extracted(800, 200, usedFeed = true)))
        assertEquals(FullTextEvidence.FEED_SHORT, FullTextCheck.evidence(extracted(150, 30, usedFeed = true)))
        assertEquals(FullTextEvidence.BLOCKED, FullTextCheck.evidence(extracted(40, null, usedFeed = true, blocked = true, note = "Couldn't fetch")))
        assertEquals(
            "a site that blocks pages but sends whole articles still gives full articles",
            FullTextEvidence.FEED_FULL,
            FullTextCheck.evidence(extracted(800, null, usedFeed = true, blocked = true, note = "Couldn't fetch")),
        )
    }

    @Test
    fun outcomesThatDontTellAreIgnored() {
        assertNull("page about as long as the feed", FullTextCheck.evidence(extracted(200, 300, usedFeed = false)))
        assertNull("no feed text to compare", FullTextCheck.evidence(extracted(0, 900, usedFeed = false)))
        assertNull("couldn't connect: maybe the phone was offline", FullTextCheck.evidence(extracted(40, null, usedFeed = true, note = "Couldn't fetch")))
        assertNull(
            "blocked with no feed text: there were no summaries to fall back on",
            FullTextCheck.evidence(extracted(0, null, usedFeed = false, note = "Couldn't fetch").copy(pageBlocked = true)),
        )
    }

    /** One piece of evidence per day, on consecutive days. */
    private fun run(start: FullTextState, vararg evidence: FullTextEvidence) =
        evidence.foldIndexed(start) { i, s, e -> FullTextCheck.next(s, e, (start.day ?: 0) + i + 1) }

    private fun FullTextState.nextDay(e: FullTextEvidence) = FullTextCheck.next(this, e, (day ?: 0) + 1)

    private val fresh = FullTextState(ContentMode.AUTO, null, 0)

    @Test
    fun settlesAfterThreeDaysInARow() {
        val two = run(fresh, FullTextEvidence.PAGE_LONGER, FullTextEvidence.PAGE_LONGER)
        assertEquals(ContentMode.AUTO, two.mode)
        assertEquals(ContentMode.PAGE, two.nextDay(FullTextEvidence.PAGE_LONGER).mode)
    }

    @Test
    fun oneBadMorningCountsOnce() {
        val morning = listOf(FullTextEvidence.BLOCKED, FullTextEvidence.BLOCKED, FullTextEvidence.BLOCKED)
            .fold(fresh) { s, e -> FullTextCheck.next(s, e, day = 20_000) }
        assertEquals(ContentMode.AUTO, morning.mode)
        assertEquals(1, morning.streak)
    }

    @Test
    fun differentReasonsForTheFeedCountTogether() {
        val settled = run(fresh, FullTextEvidence.FEED_SHORT, FullTextEvidence.BLOCKED, FullTextEvidence.BLOCKED)
        assertEquals(ContentMode.FEED, settled.mode)
        assertEquals("the latest reason is kept for the Sources screen", FullTextEvidence.BLOCKED, settled.evidence)
    }

    @Test
    fun aMixedSourceNeverSettles() {
        val mixed = run(
            fresh,
            FullTextEvidence.PAGE_LONGER, FullTextEvidence.PAGE_LONGER, FullTextEvidence.FEED_SHORT,
            FullTextEvidence.PAGE_LONGER, FullTextEvidence.PAGE_LONGER, FullTextEvidence.FEED_FULL,
        )
        assertEquals(ContentMode.AUTO, mixed.mode)
    }

    @Test
    fun aSettledSourceOnlyChangesAfterThreeInARowTheOtherWay() {
        val page = FullTextState(ContentMode.PAGE, FullTextEvidence.PAGE_LONGER, 3)
        val oneOff = run(page, FullTextEvidence.BLOCKED, FullTextEvidence.PAGE_LONGER, FullTextEvidence.BLOCKED, FullTextEvidence.BLOCKED)
        assertEquals(ContentMode.PAGE, oneOff.mode)
        assertEquals(ContentMode.FEED, oneOff.nextDay(FullTextEvidence.BLOCKED).mode)
    }
}
