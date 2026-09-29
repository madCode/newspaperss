package com.app.newspaperss.ui

import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.ui.sources.fullTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullTextLineTest {
    private val site = SourceEntity(url = "https://a.example/feed", title = "A")

    private fun settled(mode: ContentMode, evidence: FullTextEvidence) = site.copy(contentMode = mode, fullTextEvidence = evidence, fullTextStreak = 3)

    @Test
    fun saysWhatTheReaderGetsOnceTheCheckHasSettled() {
        assertNull("still checking", fullTextLine(site.copy(fullTextEvidence = FullTextEvidence.PAGE_LONGER, fullTextStreak = 2)))
        assertEquals("Full articles", fullTextLine(settled(ContentMode.PAGE, FullTextEvidence.PAGE_LONGER)))
        assertEquals("Full articles", fullTextLine(settled(ContentMode.FEED, FullTextEvidence.FEED_FULL)))
        assertEquals("Summaries only", fullTextLine(settled(ContentMode.FEED, FullTextEvidence.FEED_SHORT)))
        assertEquals("Site blocks fetching: using the summaries it sends", fullTextLine(settled(ContentMode.FEED, FullTextEvidence.BLOCKED)))
    }

    @Test
    fun aReadersChoiceIsShownAsTheirs() {
        assertEquals("Uses the text the site sends (your choice)", fullTextLine(site.copy(contentMode = ContentMode.FEED, contentModeChosen = true)))
    }
}
