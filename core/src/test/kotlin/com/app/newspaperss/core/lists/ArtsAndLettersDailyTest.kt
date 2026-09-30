package com.app.newspaperss.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArtsAndLettersDailyTest {
    private val page = javaClass.getResource("/lists/aldaily.html")!!.readText()
    private val base = ArtsAndLettersDaily.pageUrl

    private fun layoutChanged(html: String): String =
        runCatching { ArtsAndLettersDaily.links(html, base) }.exceptionOrNull().let {
            (it as? ListLayoutChangedException)?.message ?: throw AssertionError("expected ListLayoutChangedException, got $it")
        }

    @Test
    fun takesTheNewestLinkFromEachColumn() {
        val links = ArtsAndLettersDaily.links(page, base)
        assertEquals(
            listOf(
                "https://www.worksinprogress.news/p/why-really-caused-the-bronze-age",
                "https://newrepublic.com/article/214970/edgar-allan-poe-mined-misery-emily-ogden",
                "https://quillette.substack.com/p/an-open-letter-to-scott-alexander",
            ),
            links.map { it.url },
        )
        assertNull(links[0].title)
        assertEquals(
            "Perverseness drove Edgar Allan Poe to ruin, or the brink of it, again and again. It was also the source of his inspiration...",
            links[1].summary,
        )
    }

    @Test
    fun relativeLinksAreResolvedAgainstThePage() {
        val html = page.replace("https://newrepublic.com/article/214970/edgar-allan-poe-mined-misery-emily-ogden", "/go/poe")
        assertEquals("https://www.aldaily.com/go/poe", ArtsAndLettersDaily.links(html, base)[1].url)
    }

    @Test
    fun aTeaserWithAParagraphNestedInsideItIsStillRead() {
        // As on the live page on 2026-09-30: the parser closes the outer <p> at the inner one,
        // leaving the "more »" link outside any paragraph.
        val html = page.replace(
            "<p>Perverseness drove <strong>Edgar Allan Poe</strong> to ruin, or the brink of it, again and again. It was also the source of his inspiration... <a",
            "<p><p>Perverseness drove <strong>Edgar Allan Poe</strong> to ruin, or the brink of it, again and again. It was also the source of his inspiration</p>... <a",
        )
        val link = ArtsAndLettersDaily.links(html, base)[1]
        assertEquals("https://newrepublic.com/article/214970/edgar-allan-poe-mined-misery-emily-ogden", link.url)
        assertEquals(
            "Perverseness drove Edgar Allan Poe to ruin, or the brink of it, again and again. It was also the source of his inspiration...",
            link.summary,
        )
    }

    @Test
    fun aMissingColumnMeansTheLayoutChanged() {
        assertEquals("no \"New Books\" column", layoutChanged(page.replace(">New Books</a></h2>", ">Books</a></h2>")))
        layoutChanged("<html><body><p>We've redesigned! <a href=\"https://example.com/\">more »</a></p></body></html>")
    }

    @Test
    fun aNewestEntryWithoutItsLinkIsNotReplacedByAnOlderOne() {
        // The column's first entry loses its "more »" link; the second one is yesterday's pick.
        val html = page.replace(Regex("""<a href="https://www\.worksinprogress[^"]*">[^<]*</a>"""), "")
        assertEquals("the newest entry in \"Articles of Note\" has no link", layoutChanged(html))
    }

    @Test
    fun aListsSourceUrlLeadsBackToIt() {
        val url = CuratedLists.sourceUrl(ArtsAndLettersDaily)
        assertSame(ArtsAndLettersDaily, CuratedLists.forSourceUrl(url))
        assertNull(CuratedLists.forSourceUrl("newspaperss:list:gone"))
    }
}
