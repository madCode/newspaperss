package com.app.newspaperss.core.listen

import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class ListenScriptTest {
    private val png = EpubImage("images/knots.png", "image/png", byteArrayOf(1, 2, 3))

    /** The book's own pages, as the player reads them out of the EPUB. */
    private fun book(vararg articles: EditionArticle, reflection: String? = null): Map<String, String> {
        val out = ByteArrayOutputStream()
        val doc = EditionDoc(
            "Thursday Morning Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000002",
            listOf(EditionSection(null, articles.toList())), reflection = reflection,
        )
        EpubWriter.write(doc, out)
        val pages = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { pages[it.name.removePrefix("OEBPS/")] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return pages
    }

    private fun article(body: String, author: String? = "Erica Klarreich", images: List<EpubImage> = emptyList(), minutes: Double = 9.0) = EditionArticle(
        title = "The Mathematician Who Counted the Shapes of Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/knots",
        bodyHtml = body, minutes = minutes, author = author, published = LocalDate.of(2026, 9, 30), images = images,
    )

    private fun texts(script: ListenScript) = script.blocks.map { (it as? Block.Text)?.let { t -> t.kind to t.sentences.joinToString(" ") } ?: (null to (it as Block.Image).spoken) }

    @Test
    fun anArticleIsReadFromItsSourceAndTitleToItsLastSentenceAndNoFurther() {
        val pages = book(
            article("<p>For forty years, nobody could say. Then a teacher wrote a program.</p><h2>The count</h2><p>It took a week.</p>"),
            article("<p>The second.</p>"),
        )
        val script = ListenScript.parse(pages.getValue("article-001.xhtml"))
        assertEquals(
            listOf(
                Kind.KICKER to "Quanta Magazine",
                Kind.TITLE to "The Mathematician Who Counted the Shapes of Knots",
                // The author, not the date and reading time.
                Kind.BYLINE to "By Erica Klarreich",
                Kind.PARAGRAPH to "For forty years, nobody could say. Then a teacher wrote a program.",
                Kind.HEADING to "The count",
                Kind.PARAGRAPH to "It took a week.",
            ),
            texts(script),
        )
        // Each sentence is a line of its own: what ↶ goes back to. Not the link to the original
        // nor the "Next" line after a long read.
        assertEquals(
            listOf(
                "Quanta Magazine", "The Mathematician Who Counted the Shapes of Knots", "By Erica Klarreich",
                "For forty years, nobody could say.", "Then a teacher wrote a program.", "The count", "It took a week.",
            ),
            script.lines.map { it.spoken },
        )
        assertEquals("en", script.language)
    }

    @Test
    fun aPictureIsSaidInOneLineFromItsCaptionOrAltText() {
        val pages = book(
            article(
                "<p>Tait drew the first table.</p>" +
                    "<figure><img src=\"images/knots.png\" alt=\"knots\"/><figcaption>Tait's 1877 table of knots</figcaption></figure>" +
                    "<p>Before <img src=\"images/knots.png\" alt=\"A trefoil\"/> after.</p>" +
                    "<p><img src=\"images/knots.png\"/></p>",
                images = listOf(png),
            ),
        )
        val script = ListenScript.parse(pages.getValue("article-001.xhtml"))
        val body = texts(script).drop(3)
        assertEquals(
            listOf(
                Kind.PARAGRAPH to "Tait drew the first table.",
                // The caption over the alt text, and not read a second time.
                null to "Image: Tait's 1877 table of knots",
                Kind.PARAGRAPH to "Before",
                null to "Image: A trefoil",
                Kind.PARAGRAPH to "after.",
                null to "An image.",
            ),
            body,
        )
        assertEquals("images/knots.png", (script.blocks[4] as Block.Image).src)
    }

    @Test
    fun quotesListsAndTablesAreReadAsTheirOwnBlocks() {
        val script = ListenScript.parse(
            book(
                article(
                    "<blockquote><p>Knots are hard.</p></blockquote><ul><li>One</li><li>Two <b>bold</b> words</li></ul>" +
                        "<table><tr><th>Crossings</th><th>Knots</th></tr><tr><td>3</td><td>1</td></tr></table>" +
                        "<p>Text<br/>broken.</p>",
                ),
            ).getValue("article-001.xhtml"),
        )
        assertEquals(
            listOf(
                Kind.QUOTE to "Knots are hard.",
                Kind.ITEM to "One",
                Kind.ITEM to "Two bold words",
                Kind.PARAGRAPH to "Crossings, Knots",
                Kind.PARAGRAPH to "3, 1",
                Kind.PARAGRAPH to "Text broken.",
            ),
            texts(script).drop(3),
        )
    }

    @Test
    fun footnoteMarkersAreNotReadOut() {
        val script = ListenScript.parse(
            book(article("<p>A claim.<sup><a href=\"#fn1\" id=\"r1\">1</a></sup> More, see <a href=\"https://example.com\">the paper</a> and <a href=\"#notes\">here</a>.</p><p id=\"fn1\"><a href=\"#r1\">1</a> The source.</p>"))
                .getValue("article-001.xhtml"),
        )
        assertEquals(listOf("A claim.", "More, see the paper and here.", "The source."), script.lines.drop(3).map { it.spoken })
    }

    @Test
    fun withoutAnAuthorThereIsNoByline() {
        val script = ListenScript.parse(book(article("<p>Text.</p>", author = null)).getValue("article-001.xhtml"))
        assertEquals(listOf(Kind.KICKER, Kind.TITLE, Kind.PARAGRAPH), script.blocks.map { (it as Block.Text).kind })
    }

    @Test
    fun theEndPageIsReadToo() {
        val pages = book(article("<p>Text.</p>"), reflection = "What surprised you?")
        val script = ListenScript.parse(pages.getValue("end.xhtml"))
        val spoken = script.lines.map { it.spoken }
        assertEquals("That's all for today.", spoken.first())
        assertTrue(spoken.toString(), spoken.any { it.startsWith("1 article") })
        assertTrue(spoken.toString(), "To think about" in spoken && "What surprised you?" in spoken)
        // Not the imprint: the masthead and date are for the page.
        assertTrue(spoken.none { it.contains("NewspapeRSS") })
    }

    @Test
    fun anArticleInAnotherLanguageSaysSo() {
        val pages = book(article("<p>Bonjour.</p>").copy(language = "fr"))
        assertEquals("fr", ListenScript.parse(pages.getValue("article-001.xhtml")).language)
    }

    @Test
    fun listeningTakesLongerThanReading() {
        assertEquals(15.0, ListenTime.fromReading(10.0), 0.2)
        val script = ListenScript.parse(book(article("<p>" + "word ".repeat(160) + "end.</p>")).getValue("article-001.xhtml"))
        // 161 words of body at 160 a minute, after the source, title and byline.
        assertEquals(60.0, script.lines.drop(3).sumOf { ListenTime.seconds(it.spoken) }, 2.0)
        assertTrue(script.seconds > 60.0)
    }
}
