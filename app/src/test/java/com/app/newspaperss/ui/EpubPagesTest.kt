package com.app.newspaperss.ui

import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.ui.edition.EpubPages
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class EpubPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsArticlesInReadingOrderFromWhatTheWriterWrote() {
        val file = tmp.newFile("e.epub")
        val articles = listOf("First story", "Second story").map {
            EditionArticle(title = it, sourceTitle = "Src", url = "https://a.example/", bodyHtml = "<p>$it body</p>", minutes = 2.0)
        }
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, articles))), it)
        }
        val pages = EpubPages(file)

        assertTrue(pages.article(0)!!.contains("First story"))
        assertTrue(pages.article(1)!!.contains("Second story"))
        assertNull(pages.article(2))
        assertNotNull("the page's stylesheet can be served", pages.entry("OEBPS/style.css"))
    }

    @Test
    fun aMissingFileReadsAsNothing() {
        assertNull(EpubPages(tmp.root.resolve("gone.epub")).article(0))
    }

    @Test
    fun thePreviewServesOnlyTheBookAndNeverPassesARequestOn() {
        val file = tmp.newFile("b.epub")
        val article = EditionArticle(title = "A", sourceTitle = "S", url = "https://a.example/", bodyHtml = "<p>x</p>", minutes = 1.0)
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:2", listOf(EditionSection(null, listOf(article)))), it)
        }
        val pages = EpubPages(file)

        val (cssType, css) = com.app.newspaperss.ui.edition.bookResponse(com.app.newspaperss.ui.edition.BOOK_ORIGIN + "style.css", pages)
        assertTrue(css.isNotEmpty())
        assertTrue(cssType == "text/css")
        val (_, pixel) = com.app.newspaperss.ui.edition.bookResponse("https://tracker.example/pixel.gif", pages)
        assertTrue("outside the book: empty, not fetched", pixel.isEmpty())
        val (_, missing) = com.app.newspaperss.ui.edition.bookResponse(com.app.newspaperss.ui.edition.BOOK_ORIGIN + "images/none.jpg", pages)
        assertTrue(missing.isEmpty())
    }
}
