package com.app.newspaperss.ui

import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.ui.sources.fullTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullTextLineTest {
    private fun learned(mode: ContentMode, evidence: FullTextEvidence, streak: Int = 3) = PublicationEntity(1, PublicationEntity.OWN, mode, evidence, streak)

    @Test
    fun saysWhatTheReaderGetsOnceTheCheckHasSettled() {
        assertNull("nothing learned yet", fullTextLine(null))
        assertNull("still checking", fullTextLine(learned(ContentMode.AUTO, FullTextEvidence.PAGE_LONGER, streak = 2)))
        assertEquals("Full articles", fullTextLine(learned(ContentMode.PAGE, FullTextEvidence.PAGE_LONGER)))
        assertEquals("Full articles", fullTextLine(learned(ContentMode.FEED, FullTextEvidence.FEED_FULL)))
        assertEquals("Summaries only", fullTextLine(learned(ContentMode.FEED, FullTextEvidence.FEED_SHORT)))
        assertEquals("Site blocks fetching: using the summaries it sends", fullTextLine(learned(ContentMode.FEED, FullTextEvidence.BLOCKED)))
    }

    @Test
    fun aReadersChoiceIsShownAsTheirs() {
        assertEquals("Uses the text the site sends (your choice)", fullTextLine(learned(ContentMode.PAGE, FullTextEvidence.PAGE_LONGER).copy(chosenMode = ContentMode.FEED)))
    }
}
