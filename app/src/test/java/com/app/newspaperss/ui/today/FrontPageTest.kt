package com.app.newspaperss.ui.today

import com.app.newspaperss.data.EditionArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontPageTest {
    private fun article(position: Int, minutes: Double, starred: Boolean = false) =
        EditionArticleEntity(editionId = 1, articleId = position.toLong(), position = position, title = "Article $position", sourceTitle = "Source", minutes = minutes, starred = starred)

    private fun titles(list: List<EditionArticleEntity>) = list.map { it.title }

    @Test
    fun aStarLeadsOverALongerRead() {
        val page = FrontPage.of(listOf(article(0, 3.0), article(1, 12.0), article(2, 5.0, starred = true), article(3, 4.0)))!!

        assertEquals("Article 2", page.lead.title)
        assertEquals(FrontPage.Why.STARRED, page.why)
        assertEquals(listOf("Article 0", "Article 1"), titles(page.next))
        assertEquals(1, page.more)
    }

    @Test
    fun withTwoStarsTheFirstInTheBookLeads() {
        val page = FrontPage.of(listOf(article(0, 3.0, starred = true), article(1, 6.0, starred = true)))!!
        assertEquals("Article 0", page.lead.title)
    }

    @Test
    fun withoutAStarTheLongestReadLeadsAndTheRestKeepTheirOrder() {
        val page = FrontPage.of(listOf(article(2, 4.0), article(0, 3.0), article(1, 9.0), article(3, 2.0), article(4, 1.0)))!!

        assertEquals("Article 1", page.lead.title)
        assertEquals(FrontPage.Why.LONGEST, page.why)
        assertEquals(listOf("Article 0", "Article 2"), titles(page.next))
        assertEquals(2, page.more)
    }

    @Test
    fun aTieForLongestIsNoReasonToLead() {
        val page = FrontPage.of(listOf(article(0, 3.0), article(1, 8.0), article(2, 8.0)))!!

        assertEquals("Article 0", page.lead.title)
        assertNull(page.why)
    }

    @Test
    fun aSingleArticleLeadsWithoutAReason() {
        val page = FrontPage.of(listOf(article(0, 5.0)))!!

        assertNull(page.why)
        assertEquals(0, page.next.size)
        assertEquals(0, page.more)
    }

    @Test
    fun noArticlesNoFrontPage() = assertNull(FrontPage.of(emptyList()))
}
