package com.app.newspaperss.core.epub

import com.app.newspaperss.core.extract.HtmlCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {


    private fun article(
        title: String = "An article",
        source: String = "Example Daily",
        body: String = "<p>Hello world.</p>",
        minutes: Double = 3.0,
        url: String = "https://example.com/a",
        author: String? = null,
        published: LocalDate? = null,
        note: String? = null,
        images: List<EpubImage> = emptyList(),
        language: String? = null,
    ) = EditionArticle(
        title = title, sourceTitle = source, url = url, bodyHtml = body, minutes = minutes,
        author = author, published = published, note = note, images = images, language = language,
    )

    private fun doc(vararg sections: EditionSection, title: String = "Tuesday Morning Edition") = EditionDoc(
        title = title,
        date = LocalDate.of(2026, 9, 29),
        identifier = "urn:uuid:5b1f3c8e-0000-4000-8000-000000000001",
        sections = sections.toList(),
        modified = Instant.parse("2026-09-29T06:30:12.345Z"),
    )

    private fun unsectioned(vararg articles: EditionArticle) = doc(EditionSection(null, articles.toList()))

    private class Epub(val raw: ByteArray, val entries: List<Pair<ZipEntry, ByteArray>>) {
        val files: Map<String, ByteArray> = entries.associate { it.first.name to it.second }
        fun text(name: String) = String(files.getValue(name), Charsets.UTF_8)
        fun xml(name: String): Document = parseXml(files.getValue(name))
    }

    private fun write(doc: EditionDoc): Epub {
        val out = ByteArrayOutputStream()
        EpubWriter.write(doc, out)
        val raw = out.toByteArray()
        val entries = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry to zip.readBytes()
            }
        }
        return Epub(raw, entries)
    }

    private companion object {
        fun parseXml(bytes: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
            }
            val builder = factory.newDocumentBuilder()
            // The default handler prints to stderr and carries on; well-formedness errors must fail.
            builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) = Unit
                override fun error(e: org.xml.sax.SAXParseException) = throw e
                override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
            })
            return builder.parse(ByteArrayInputStream(bytes))
        }
    }

    private fun Node.elements(localName: String): List<Element> {
        val result = mutableListOf<Element>()
        fun walk(node: Node) {
            if (node is Element && (localName == "*" || node.localName == localName)) result += node
            val children = node.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(this)
        return result
    }

    private fun Epub.opf() = xml("OEBPS/content.opf")

    /** Manifest hrefs by id. */
    private fun Epub.manifest(): Map<String, Element> =
        opf().elements("item").associateBy { it.getAttribute("id") }

    private fun Epub.spineHrefs(): List<String> {
        val manifest = manifest()
        return opf().elements("itemref").map { manifest.getValue(it.getAttribute("idref")).getAttribute("href") }
    }

    private fun Epub.articleHrefs() = spineHrefs().drop(2).dropLast(1)

    private fun Epub.xhtmlNames() = files.keys.filter { it.endsWith(".xhtml") }

    private fun Epub.navHrefs(): List<String> {
        val toc = xml("OEBPS/nav.xhtml").elements("nav").single { it.getAttributeNS(EPUB_NS, "type") == "toc" }
        return toc.elements("a").map { it.getAttribute("href") }
    }

    private fun Epub.ncxHrefs() = xml("OEBPS/toc.ncx").elements("content").map { it.getAttribute("src") }

    private val EPUB_NS = "http://www.idpf.org/2007/ops"


    @Test
    fun mimetypeIsTheFirstEntryStoredUncompressedWithNoExtraField() {
        val epub = write(unsectioned(article()))
        val raw = epub.raw
        // Readers sniff the file type from these fixed offsets of the first local file header.
        assertEquals("PK\u0003\u0004", String(raw, 0, 4, Charsets.ISO_8859_1))
        assertEquals(0, raw[8].toInt() or (raw[9].toInt() shl 8)) // compression method: stored
        assertEquals(0, raw[28].toInt() or (raw[29].toInt() shl 8)) // extra field length
        assertEquals("mimetype", String(raw, 30, 8, Charsets.US_ASCII))
        assertEquals("application/epub+zip", String(raw, 38, 20, Charsets.US_ASCII))
        assertEquals("mimetype", epub.entries.first().first.name)
    }

    @Test
    fun containerPointsAtThePackageDocument() {
        val epub = write(unsectioned(article()))
        val rootfile = epub.xml("META-INF/container.xml").elements("rootfile").single()
        assertEquals("OEBPS/content.opf", rootfile.getAttribute("full-path"))
        assertNotNull(epub.files["OEBPS/content.opf"])
    }

    @Test
    fun manifestAndZipContainExactlyTheSameFiles() {
        val image = EpubImage("images/a1-0.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val epub = write(
            doc(
                EditionSection("World", listOf(article(body = "<p><img src=\"images/a1-0.jpg\" alt=\"\"></p>", images = listOf(image)))),
                EditionSection(null, listOf(article(title = "Second"))),
            ),
        )
        val manifestFiles = epub.manifest().values.map { "OEBPS/" + it.getAttribute("href") }.toSet()
        val zipFiles = epub.files.keys - setOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf")
        assertEquals(zipFiles, manifestFiles)
        assertEquals(epub.entries.size, epub.files.size) // no duplicate entry names
    }

    @Test
    fun aComicsHoverTextCaptionReachesTheBook() {
        val cleaned = HtmlCleaner.clean(
            "<img src=\"https://imgs.xkcd.com/comics/sandwich.png\" title=\"Proper User Policy apparently means Simon Says.\" alt=\"Sandwich\" />",
            "https://xkcd.com/149/",
        ).html.replace("https://imgs.xkcd.com/comics/sandwich.png", "images/a1-0.png")
        val epub = write(unsectioned(article(body = cleaned, images = listOf(EpubImage("images/a1-0.png", "image/png", byteArrayOf(1))))))

        val page = epub.xml("OEBPS/" + epub.articleHrefs().single())
        val figure = page.documentElement.elements("figure").single()
        assertEquals(listOf("figure", "img", "figcaption"), figure.elements("*").map { it.localName })
        assertEquals("Proper User Policy apparently means Simon Says.", figure.elements("figcaption").single().textContent)
    }

    @Test
    fun packageMetadataIsComplete() {
        val epub = write(unsectioned(article()))
        val opf = epub.opf()
        val pkg = opf.documentElement
        val uniqueId = pkg.getAttribute("unique-identifier")
        val identifier = opf.elements("identifier").single()
        assertEquals(uniqueId, identifier.getAttribute("id"))
        assertEquals("urn:uuid:5b1f3c8e-0000-4000-8000-000000000001", identifier.textContent)
        assertEquals("Tuesday Morning Edition", opf.elements("title").single().textContent)
        assertEquals("en", opf.elements("language").single().textContent)
        assertEquals("2026-09-29", opf.elements("date").single().textContent)
        val modified = opf.elements("meta").single { it.getAttribute("property") == "dcterms:modified" }
        // EPUB requires exactly CCYY-MM-DDThh:mm:ssZ, without fractional seconds.
        assertEquals("2026-09-29T06:30:12Z", modified.textContent)
    }


    @Test
    fun spineIsCoverContentsArticlesThenEndAllLinear() {
        val epub = write(
            doc(
                EditionSection("World", listOf(article(title = "W1"), article(title = "W2"))),
                EditionSection("Empty", emptyList()),
                EditionSection("Science", listOf(article(title = "S1"))),
            ),
        )
        val spine = epub.spineHrefs()
        assertEquals(6, spine.size)
        assertTrue(epub.text("OEBPS/" + spine[0]).contains("Tuesday Morning Edition"))
        assertTrue(epub.text("OEBPS/" + spine[1]).contains("In this edition</h1>"))
        val titles = spine.drop(2).dropLast(1).map { epub.xml("OEBPS/$it").elements("h1").single().textContent }
        assertEquals(listOf("W1", "W2", "S1"), titles)
        assertTrue(epub.text("OEBPS/" + spine.last()).contains("That's all for today."))
        assertTrue(epub.opf().elements("itemref").none { it.getAttribute("linear") == "no" })
    }

    @Test
    fun guidePointsAtCoverAndContentsAndKindleOpensAtTheContents() {
        val epub = write(unsectioned(article(), article()))
        val refs = epub.opf().elements("reference").associate { it.getAttribute("type") to it.getAttribute("href") }
        val spine = epub.spineHrefs()
        assertEquals(spine[0], refs["cover"])
        assertEquals(spine[1], refs["toc"])
        assertEquals(spine[1], refs["text"])
    }

    @Test
    fun navAndNcxListTheSameTargetsInTheSameOrder() {
        val epub = write(
            doc(
                EditionSection(null, listOf(article(title = "Lead story"))),
                EditionSection("World", listOf(article(title = "W1"), article(title = "W2"))),
                EditionSection("Science", listOf(article(title = "S1"))),
            ),
        )
        val nav = epub.navHrefs()
        assertEquals(nav, epub.ncxHrefs())
        val articleHrefs = epub.articleHrefs()
        // Every entry after Contents is in reading order: a section opens at its first article, and the
        // closing page is a chapter of its own, last.
        assertEquals(articleHrefs + "end.xhtml", nav.drop(1).distinct())
        assertEquals(nav.drop(1), nav.drop(1).sortedBy { (articleHrefs + "end.xhtml").indexOf(it) })

        val labels = epub.xml("OEBPS/toc.ncx").elements("text").drop(1).map { it.textContent } // skip docTitle
        assertEquals(listOf("Contents", "Lead story", "World", "W1", "W2", "Science", "S1", "That's all for today"), labels)
    }

    @Test
    fun sectionTitlesNestTheirArticlesOneLevelDeep() {
        val epub = write(
            doc(
                EditionSection("World", listOf(article(title = "W1"), article(title = "W2"))),
                EditionSection(" ", listOf(article(title = "Loose"))),
            ),
        )
        val tocNav = epub.xml("OEBPS/nav.xhtml").elements("nav").first()
        val topLevel = (tocNav.elements("ol").first().childNodes).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
        }
        assertEquals(listOf("Contents", "World", "Loose", "That's all for today"), topLevel.map { it.elements("a").first().textContent })
        assertEquals(listOf("World", "W1", "W2"), topLevel[1].elements("a").map { it.textContent })

        val ncx = epub.xml("OEBPS/toc.ncx")
        val world = ncx.elements("navPoint").single { it.elements("text").first().textContent == "World" }
        assertEquals(listOf("W1", "W2"), world.elements("navPoint").drop(1).map { it.elements("text").first().textContent })
        assertEquals("2", ncx.elements("meta").single { it.getAttribute("name") == "dtb:depth" }.getAttribute("content"))
        // "World" and W1 open the same page, so they share a playOrder.
        val playOrders = ncx.elements("navPoint").map { it.getAttribute("playOrder").toInt() }
        assertEquals(listOf(1, 2, 2, 3, 4, 5), playOrders)
    }

    @Test
    fun everyLinkInsideTheBookResolves() {
        val epub = write(
            doc(
                EditionSection("World", listOf(article(title = "W1", body = "<p id=\"x\"><a href=\"#x\">self</a></p>"), article(title = "W2"))),
                EditionSection(null, listOf(article(title = "Loose"))),
            ),
        )
        for (name in epub.xhtmlNames()) {
            for (a in epub.xml(name).elements("a")) {
                val href = a.getAttribute("href")
                if (href.isEmpty() || href.startsWith("http") || href.startsWith("mailto:")) continue
                val file = href.substringBefore('#').ifEmpty { name.removePrefix("OEBPS/") }
                assertTrue("$name links to missing $href", "OEBPS/$file" in epub.files)
                if ('#' in href) {
                    val ids = epub.xml("OEBPS/$file").elements("*").map { it.getAttribute("id") }
                    assertTrue("$name links to missing anchor $href", href.substringAfter('#') in ids)
                }
            }
        }
    }


    @Test
    fun coverSummarisesTheEdition() {
        val sources = listOf("Alpha", "Beta", "Alpha", "Gamma", "Delta", "Epsilon")
        val epub = write(unsectioned(*sources.map { article(source = it, minutes = 4.4) }.toTypedArray()))
        val cover = epub.xml("OEBPS/" + epub.spineHrefs().first()).documentElement.textContent
        assertTrue(cover.contains("newspapeRSS"))
        assertTrue(cover.contains("Tuesday Morning Edition"))
        assertTrue(cover.contains("Tuesday, September 29, 2026"))
        assertTrue(cover.contains("6 articles · 26 min"))
        assertTrue(cover.contains("Alpha, Beta, Gamma, Delta and more"))
        assertFalse(cover.contains("Epsilon"))
    }

    @Test
    fun coverListsAllSourcesWhenThereAreFewAndUsesSingularForOneArticle() {
        val epub = write(unsectioned(article(source = "Alpha", minutes = 0.3)))
        val cover = epub.xml("OEBPS/cover.xhtml").documentElement.textContent
        assertTrue(cover.contains("1 article · 1 min"))
        assertTrue(cover.contains("Alpha"))
        assertFalse(cover.contains("and more"))
    }

    @Test
    fun contentsPageListsEachArticleWithSourceAndMinutes() {
        val epub = write(
            unsectioned(
                article(title = "Short", source = "Alpha", minutes = 0.2),
                article(title = "Long", source = "Beta", minutes = 61.6),
            ),
        )
        val contents = epub.xml("OEBPS/contents.xhtml")
        val items = contents.elements("li")
        assertEquals(2, items.size)
        assertEquals("Short", items[0].elements("a").single().textContent)
        assertEquals("Alpha · 1 min", items[0].elements("span").single().textContent)
        assertEquals("Beta · 1 hr 2 min", items[1].elements("span").single().textContent)
        assertEquals(epub.articleHrefs(), items.map { it.elements("a").single().getAttribute("href") })
        assertTrue(contents.documentElement.textContent.contains("2 articles · 1 hr 2 min"))
    }

    @Test
    fun articlePageHasBylineNoteOriginalLinkAndNextLink() {
        val epub = write(
            unsectioned(
                article(
                    title = "First", source = "Alpha", author = "Ada Lovelace", published = LocalDate.of(2026, 9, 28),
                    minutes = 6.6, note = "Couldn't fetch the full article; showing the feed's version",
                    url = "https://example.com/first",
                ),
                article(title = "Second", url = "https://example.com/second"),
            ),
        )
        val (first, second) = epub.articleHrefs()
        val page = epub.xml("OEBPS/$first")
        val paragraphs = page.elements("p").associateBy { it.getAttribute("class") }
        assertEquals("Alpha", paragraphs.getValue("kicker").textContent)
        assertEquals("By Ada Lovelace · Sep 28, 2026 · 7 min read", paragraphs.getValue("byline").textContent)
        assertEquals("Couldn't fetch the full article; showing the feed's version", paragraphs.getValue("note").textContent)
        val original = paragraphs.getValue("source-link").elements("a").single()
        assertEquals("https://example.com/first", original.getAttribute("href"))
        assertEquals("the site's name, not a long unbreakable address", "example.com", original.textContent)
        val next = paragraphs.getValue("article-nav").elements("a").single()
        assertEquals(second, next.getAttribute("href"))
        assertEquals("Second", next.textContent)

        val last = epub.xml("OEBPS/$second").elements("p").map { it.getAttribute("class") }
        assertFalse("the last article has no Next link", "article-nav" in last)
        assertFalse("no note unless given", "note" in last)
    }

    @Test
    fun bylineLeavesOutMissingParts() {
        val epub = write(unsectioned(article(source = "Alpha", minutes = 2.0)))
        val byline = epub.xml("OEBPS/" + epub.articleHrefs().single()).elements("p")
            .single { it.getAttribute("class") == "byline" }
        assertEquals("2 min read", byline.textContent)
    }

    @Test
    fun theKickerIsTheSourceAndTheOriginalLinkNamesTheSite() {
        val epub = write(unsectioned(article(source = "", url = "https://www.example.com/a/very/long/path?with=tracking")))
        val paragraphs = epub.xml("OEBPS/" + epub.articleHrefs().single()).elements("p")
        assertTrue("no kicker without a source", paragraphs.none { it.getAttribute("class") == "kicker" })
        assertEquals("Read the original at example.com", paragraphs.single { it.getAttribute("class") == "source-link" }.textContent)
    }

    @Test
    fun blankTitlesGetAPlaceholder() {
        val epub = write(unsectioned(article(title = "  ")))
        assertEquals("Article 1", epub.xml("OEBPS/" + epub.articleHrefs().single()).elements("h1").single().textContent)
        assertEquals("Article 1", epub.xml("OEBPS/toc.ncx").elements("text").dropLast(1).last().textContent)
    }

    @Test
    fun anEditionWithNoArticlesIsStillAValidBook() {
        val epub = write(doc())
        assertEquals(listOf("cover.xhtml", "contents.xhtml", "end.xhtml"), epub.spineHrefs())
        epub.xhtmlNames().forEach { epub.xml(it) }
        assertEquals(epub.navHrefs(), epub.ncxHrefs())
    }


    @Test
    fun specialCharactersInTextSurviveEverywhere() {
        val nasty = "Tom & Jerry <3 \"quoted\" 'single' 🎉 é"
        val epub = write(
            doc(
                EditionSection(nasty, listOf(article(title = nasty, source = nasty, author = nasty, note = nasty))),
                title = nasty,
            ),
        )
        val articleHref = epub.articleHrefs().single()
        assertEquals(nasty, epub.opf().elements("title").single().textContent)
        assertEquals(nasty, epub.xml("OEBPS/$articleHref").elements("h1").single().textContent)
        assertEquals(nasty, epub.xml("OEBPS/$articleHref").elements("title").single().textContent)
        assertTrue(epub.xml("OEBPS/$articleHref").elements("p").first().textContent.startsWith(nasty))
        assertEquals(listOf("Contents", nasty, nasty, "That's all for today"), epub.xml("OEBPS/toc.ncx").elements("text").drop(1).map { it.textContent })
        assertTrue(epub.xml("OEBPS/nav.xhtml").elements("a").any { it.textContent == nasty })
        assertTrue(epub.xml("OEBPS/cover.xhtml").documentElement.textContent.contains(nasty))
        assertTrue(epub.xml("OEBPS/contents.xhtml").elements("h2").single().textContent.startsWith(nasty))
    }

    @Test
    fun controlCharactersAreDroppedInsteadOfBreakingTheXml() {
        val epub = write(unsectioned(article(title = "Bell\u0007 title", body = "<p>form\u000Cfeed &#1; ok</p>")))
        val page = epub.xml("OEBPS/" + epub.articleHrefs().single())
        assertEquals("Bell title", page.elements("h1").single().textContent)
        assertTrue(page.documentElement.textContent.contains("formfeed  ok"))
    }

    @Test
    fun messyWebHtmlBecomesStrictXhtml() {
        val body = """
            <!-- a comment -- with double hyphens -->
            <p>Unclosed paragraph with&nbsp;entity &copy; and <b>bold
            <p>Line<br>break and <img src="https://cdn.example.com/x.jpg"> remote image
            <o:p>Word markup</o:p>
            <div onclick="steal()" style="color:red" xlink:href="x" @click="y" data-ok="1">attrs</div>
            <script>document.write("<p>injected</p>")</script>
            <style>p { color: red }</style>
            <iframe src="https://example.com/embed"></iframe>
            <svg><circle r="1"/></svg>
            <table><tr><td>cell<td>cell 2</table>
            <a href="javascript:alert(1)">js link</a>
            <a href="/relative/path">relative link</a>
            <a href="https://example.com/a b?q=1&amp;r=2#frag#more">odd link</a>
            <input type="checkbox" checked>
            <p hidden>hidden attr</p>
        """.trimIndent()
        val epub = write(unsectioned(article(body = body)))
        val name = "OEBPS/" + epub.articleHrefs().single()
        val page = epub.xml(name)
        val text = page.documentElement.textContent
        val raw = epub.text(name)

        assertTrue(text.contains("Unclosed paragraph with entity © and bold"))
        assertTrue(text.contains("Word markup"))
        assertFalse(text.contains("injected"))
        assertFalse(raw.contains("<script"))
        assertFalse(raw.contains("<style>p"))
        assertFalse(raw.contains("<iframe"))
        assertFalse(raw.contains("<svg"))
        assertFalse(raw.contains("onclick"))
        assertFalse(raw.contains("click=\"y\""))
        assertFalse(raw.contains("style=\"color"))
        assertFalse(raw.contains("cdn.example.com"))
        assertEquals(2, page.elements("td").size)
        assertTrue(page.elements("div").any { it.getAttribute("data-ok") == "1" })

        val links = page.elements("a").associate { it.textContent to it.getAttribute("href") }
        assertNull("javascript: links are unwrapped", links["js link"])
        assertNull("relative links point at nothing in the book", links["relative link"])
        assertTrue(text.contains("js link") && text.contains("relative link"))
        assertEquals("https://example.com/a%20b?q=1&r=2#frag%23more", links["odd link"])
    }

    @Test
    fun everyXhtmlFileParsesAsNamespacedXhtml() {
        val epub = write(
            doc(
                EditionSection("A & B", listOf(article(title = "<script>", body = "<p>x<br>y</p><hr><ul><li>a<li>b</ul>"))),
                EditionSection(null, listOf(article(title = "Plain"))),
            ),
        )
        val names = epub.xhtmlNames()
        assertEquals(epub.manifest().values.count { it.getAttribute("media-type") == "application/xhtml+xml" }, names.size)
        for (name in names) {
            val root = epub.xml(name).documentElement
            assertEquals(name, "http://www.w3.org/1999/xhtml", root.namespaceURI)
            assertEquals(name, "html", root.localName)
        }
        epub.xml("OEBPS/toc.ncx")
        epub.xml("OEBPS/content.opf")
    }

    /** A Kindle's footnote popup shows only what the marker points at, so that must be the footnote's text, not its number. */
    @Test
    fun aFootnoteMarkerPointsAtTheWholeFootnote() {
        val body = "<p>Some context.<a id=\"footnote-anchor-3\" href=\"#footnote-3\">3</a><a id=\"footnote-anchor-4\" href=\"#footnote-4\">4</a></p>" +
            "<div><a id=\"footnote-3\" href=\"#footnote-anchor-3\">3</a><div><p>Read the first one.</p></div></div>" +
            "<div><a id=\"footnote-4\" href=\"#footnote-anchor-4\">4</a><div><p>Read the second one.</p></div></div>"
        val page = write(unsectioned(article(body = body))).let { it.xml("OEBPS/" + it.articleHrefs().single()) }
        val byId = page.elements("*").filter { it.getAttribute("id").isNotEmpty() }.associateBy { it.getAttribute("id") }
        val paragraph = page.elements("p").single { it.textContent.startsWith("Some context") }
        val markers = page.elements("a").filter { it.parentNode === paragraph }
        assertEquals("side by side, each is its own word to tap", "Some context.3, 4", paragraph.textContent)
        for ((marker, text) in markers.zip(listOf("Read the first one.", "Read the second one."))) {
            val footnote = byId.getValue(marker.getAttribute("href").removePrefix("#"))
            assertEquals("div", footnote.localName)
            assertTrue(footnote.textContent, text in footnote.textContent)
            // The number still links back to the text.
            val back = footnote.elements("a").first()
            assertEquals(marker, byId.getValue(back.getAttribute("href").removePrefix("#")))
        }
    }

    /** A marker opening a paragraph isn't a footnote, and one block holding every footnote can't stand for one of them. */
    @Test
    fun onlyAFootnoteOnItsOwnTakesItsNumbersPlace() {
        val body = "<p><a id=\"r1\" href=\"#n1\">1</a> Starts a paragraph.<a id=\"r2\" href=\"#n2\">2</a></p>" +
            "<p><a id=\"n1\" href=\"#r1\">1</a> First.<br/><a id=\"n2\" href=\"#r2\">2</a> Second.</p>"
        val page = write(unsectioned(article(body = body))).let { it.xml("OEBPS/" + it.articleHrefs().single()) }
        assertTrue(page.elements("p").none { it.getAttribute("id").isNotEmpty() })
    }

    /** Footnotes that already keep their id on the footnote (WordPress's list items) are left as they are. */
    @Test
    fun aFootnoteWithItsIdOnTheBlockStaysAsItIs() {
        val body = "<p>Text<a id=\"fnref1\" href=\"#fn1\">1</a> and more.</p><ol><li id=\"fn1\"><p>The note. <a href=\"#fnref1\">↩</a></p></li></ol>"
        val page = write(unsectioned(article(body = body))).let { it.xml("OEBPS/" + it.articleHrefs().single()) }
        val marker = page.elements("a").single { it.textContent == "1" }
        val target = page.elements("*").single { it.getAttribute("id") == marker.getAttribute("href").removePrefix("#") }
        assertEquals("li", target.localName)
        assertTrue(page.elements("p").any { it.textContent == "Text1 and more." })
    }

    @Test
    fun idsAreUniqueAcrossTheBookAndFragmentLinksFollowThem() {
        val body = "<h2 id=\"intro\">Intro</h2><p id=\"intro\">dup</p><p><a href=\"#intro\">back to intro</a> <a href=\"#missing\">gone</a></p>"
        val epub = write(
            doc(
                EditionSection("S", listOf(article(title = "One", body = body), article(title = "Two", body = body))),
            ),
        )
        val allIds = mutableListOf<String>()
        for (name in epub.xhtmlNames()) {
            val ids = epub.xml(name).elements("*").map { it.getAttribute("id") }.filter { it.isNotEmpty() }
            allIds += ids
        }
        assertEquals(allIds.distinct(), allIds)

        for (href in epub.articleHrefs()) {
            val page = epub.xml("OEBPS/$href")
            val ids = page.elements("*").map { it.getAttribute("id") }.filter { it.isNotEmpty() }.toSet()
            val intro = page.elements("a").single { it.textContent == "back to intro" }.getAttribute("href")
            assertTrue(intro.removePrefix("#") in ids)
            assertEquals("Intro", page.elements("h2").single { it.getAttribute("id") == intro.removePrefix("#") }.textContent)
            assertTrue("a link to a missing id is unwrapped", page.elements("a").none { it.textContent == "gone" })
        }
    }

    @Test
    fun originalUrlThatIsNotAValidHrefIsShownAsText() {
        val epub = write(unsectioned(article(url = "not a url <really>")))
        val link = epub.xml("OEBPS/" + epub.articleHrefs().single()).elements("p")
            .single { it.getAttribute("class") == "source-link" }
        assertEquals("Original: not a url <really>", link.textContent)
        assertTrue(link.elements("a").isEmpty())
    }


    @Test
    fun imagesAreStoredWithTheirMediaTypesAndReferencedFromTheBody() {
        val jpeg = EpubImage("images/a1-0.jpg", "image/jpeg", byteArrayOf(9, 8, 7))
        val png = EpubImage("images/a2-0.png", "image/png", byteArrayOf(1))
        val epub = write(
            unsectioned(
                article(
                    body = "<figure><img src=\"images/a1-0.jpg\" srcset=\"https://x/y.jpg 2x\" loading=\"lazy\"><figcaption>cap</figcaption></figure>" +
                        "<figure><img src=\"https://cdn.example.com/gone.jpg\"></figure>",
                    images = listOf(jpeg),
                ),
                article(body = "<p><img src=\"images/a2-0.png\" alt=\"A chart\"></p>", images = listOf(png)),
            ),
        )
        val manifest = epub.manifest().values.associateBy { it.getAttribute("href") }
        assertEquals("image/jpeg", manifest.getValue("images/a1-0.jpg").getAttribute("media-type"))
        assertEquals("image/png", manifest.getValue("images/a2-0.png").getAttribute("media-type"))
        assertTrue(epub.files.getValue("OEBPS/images/a1-0.jpg").contentEquals(byteArrayOf(9, 8, 7)))

        val (first, second) = epub.articleHrefs()
        val firstPage = epub.xml("OEBPS/$first")
        val img = firstPage.elements("img").single()
        assertEquals("images/a1-0.jpg", img.getAttribute("src"))
        assertFalse(img.hasAttribute("srcset"))
        assertTrue("img needs an alt attribute", img.hasAttribute("alt"))
        assertEquals("a figure left without its image is removed", 1, firstPage.elements("figure").size)
        assertEquals("A chart", epub.xml("OEBPS/$second").elements("img").single().getAttribute("alt"))
    }

    @Test
    fun theSameImageSharedByTwoArticlesIsStoredOnce() {
        val shared = EpubImage("images/logo.png", "image/png", byteArrayOf(5))
        val body = "<p><img src=\"images/logo.png\"></p>"
        val epub = write(unsectioned(article(body = body, images = listOf(shared)), article(body = body, images = listOf(EpubImage("images/logo.png", "image/png", byteArrayOf(5))))))
        assertEquals(1, epub.entries.count { it.first.name == "OEBPS/images/logo.png" })
        epub.articleHrefs().forEach { assertEquals(1, epub.xml("OEBPS/$it").elements("img").size) }
    }

    @Test
    fun aCoverImageIsTheBooksCoverForEpub3AndEpub2ReadersAndFillsTheCoverPage() {
        val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 4, 2)
        val photo = EpubImage("images/a1-0.jpg", "image/jpeg", byteArrayOf(9))
        val epub = write(
            unsectioned(article(body = "<p><img src=\"images/a1-0.jpg\"></p>", images = listOf(photo)))
                .copy(cover = EpubImage("images/cover.jpg", "image/jpeg", coverBytes)),
        )

        val manifest = epub.manifest().values
        val coverItems = manifest.filter { it.getAttribute("properties").split(" ").contains("cover-image") }
        assertEquals("exactly one manifest item is the cover image", 1, coverItems.size)
        val coverItem = coverItems.single()
        assertEquals("images/cover.jpg", coverItem.getAttribute("href"))
        assertEquals("image/jpeg", coverItem.getAttribute("media-type"))
        assertTrue(epub.files.getValue("OEBPS/images/cover.jpg").contentEquals(coverBytes))
        assertEquals(1, epub.entries.count { it.first.name == "OEBPS/images/cover.jpg" })
        assertEquals("article images aren't marked as the cover", "", manifest.single { it.getAttribute("href") == "images/a1-0.jpg" }.getAttribute("properties"))

        val metaCover = epub.opf().elements("meta").single { it.getAttribute("name") == "cover" }
        assertEquals("Kindle looks the cover up by manifest id", coverItem.getAttribute("id"), metaCover.getAttribute("content"))

        val coverPage = epub.xml("OEBPS/cover.xhtml")
        assertEquals("http://www.w3.org/1999/xhtml", coverPage.documentElement.namespaceURI)
        val img = coverPage.elements("img").single()
        assertEquals("images/cover.jpg", img.getAttribute("src"))
        assertTrue(img.getAttribute("alt").contains("Tuesday Morning Edition"))
        assertEquals("the cover page stays first in the spine", "cover.xhtml", epub.spineHrefs().first())
        val manifestFiles = manifest.map { "OEBPS/" + it.getAttribute("href") }.toSet()
        assertEquals(epub.files.keys - setOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf"), manifestFiles)
    }

    @Test
    fun withoutACoverImageTheCoverPageIsTextAndNothingClaimsToBeTheCoverImage() {
        val epub = write(unsectioned(article(source = "Alpha")))
        assertTrue(epub.manifest().values.none { it.getAttribute("properties").contains("cover-image") })
        assertTrue(epub.opf().elements("meta").none { it.getAttribute("name") == "cover" })
        val coverPage = epub.xml("OEBPS/cover.xhtml")
        assertTrue(coverPage.elements("img").isEmpty())
        assertTrue(coverPage.documentElement.textContent.contains("Tuesday Morning Edition"))
    }

    @Test
    fun aCoverImageWithABadHrefOrTypeOrAnArticleImagesHrefIsRejected() {
        val articleImage = EpubImage("images/a1-0.jpg", "image/jpeg", byteArrayOf(1))
        val cases = listOf(
            EpubImage("cover.jpg", "image/jpeg", byteArrayOf(1)),
            EpubImage("images/cover.webp", "image/webp", byteArrayOf(1)),
            EpubImage("images/a1-0.jpg", "image/jpeg", byteArrayOf(2)),
        )
        for (cover in cases) {
            val out = ByteArrayOutputStream()
            assertThrows(cover.href, IllegalArgumentException::class.java) {
                EpubWriter.write(unsectioned(article(images = listOf(articleImage))).copy(cover = cover), out)
            }
            assertEquals(0, out.size())
        }
    }

    @Test
    fun invalidImagesAreRejectedBeforeAnythingIsWritten() {
        val cases = listOf(
            EpubImage("images/a.webp", "image/webp", byteArrayOf(1)),
            EpubImage("images/a.svg", "image/svg+xml", byteArrayOf(1)),
            EpubImage("../escape.jpg", "image/jpeg", byteArrayOf(1)),
            EpubImage("cover.xhtml", "image/jpeg", byteArrayOf(1)),
            EpubImage("images/a b.jpg", "image/jpeg", byteArrayOf(1)),
        )
        for (image in cases) {
            val out = ByteArrayOutputStream()
            assertThrows(image.href, IllegalArgumentException::class.java) {
                EpubWriter.write(unsectioned(article(images = listOf(image))), out)
            }
            assertEquals(0, out.size())
        }
        val clash = unsectioned(
            article(images = listOf(EpubImage("images/x.jpg", "image/jpeg", byteArrayOf(1)))),
            article(images = listOf(EpubImage("images/x.jpg", "image/jpeg", byteArrayOf(2)))),
        )
        assertThrows(IllegalArgumentException::class.java) { EpubWriter.write(clash, ByteArrayOutputStream()) }
    }

    @Test
    fun aLinkedFigureWhoseImageIsLeftOutGoesWithItsCaption() {
        val body = """<p>Text.</p><figure><a href="https://example.com/big"><img src="images/a1-0.jpg"/></a><figcaption>Orphan caption</figcaption></figure>"""
        val epub = write(unsectioned(article(body = body)))
        val page = epub.text(epub.articleHrefs().single().let { "OEBPS/$it" })
        assertFalse(page.contains("Orphan caption"))
        assertFalse(page.contains("https://example.com/big"))
    }

    @Test
    fun anArticleInAnotherLanguageIsTaggedSoTheReaderHyphenatesAndLaysItOutRight() {
        val epub = write(unsectioned(
            article(title = "Le vélo en ville", language = "fr"),
            article(title = "الدراجات في المدينة", language = "ar"),
            article(title = "Cycling in town", language = "en"),
        ))
        val pages = epub.articleHrefs().map { epub.xml("OEBPS/$it") }
        fun Document.tagged(cls: String) = documentElement.elements("*").single { it.getAttribute("class") == cls }

        for (cls in listOf("article-title", "article-body")) {
            val fr = pages[0].tagged(cls)
            assertEquals("fr", fr.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang"))
            assertEquals("fr", fr.getAttribute("lang"))
            assertEquals("", fr.getAttribute("dir"))
            assertEquals("rtl", pages[1].tagged(cls).getAttribute("dir"))
            // The book is English already.
            assertFalse(pages[2].tagged(cls).hasAttribute("lang"))
        }
        // The page's own text (source, byline, "Read the original") stays English.
        assertEquals("en", pages[0].documentElement.getAttribute("lang"))
        // Headlines are tagged wherever they appear, so an Arabic one lays out right in the contents too.
        // On the whole entry, so it's right-aligned as one.
        val entries = epub.xml("OEBPS/contents.xhtml").documentElement.elements("li")
        assertEquals("rtl", entries.single { li -> li.elements("a").single().getAttribute("href") == epub.articleHrefs()[1] }.getAttribute("dir"))
    }
    @Test
    fun anArticleWithoutWordsShowsNoReadingTime() {
        val epub = write(unsectioned(article(title = "Comic", source = "xkcd", minutes = 0.0), article(minutes = 2.0)))
        val (comic, _) = epub.articleHrefs()
        assertNull(epub.xml("OEBPS/$comic").elements("p").firstOrNull { it.getAttribute("class") == "byline" })
        assertEquals("xkcd", epub.xml("OEBPS/contents.xhtml").elements("span").first().textContent)
    }

    @Test
    fun aSectionOrEditionOfOnlyComicsShowsNoZeroMinutes() {
        val epub = write(doc(EditionSection("Fun", listOf(article(title = "Comic", source = "xkcd", minutes = 0.0)))))
        val contents = epub.xml("OEBPS/contents.xhtml")
        assertEquals("Fun", contents.elements("h2").single().textContent.trim())
        assertEquals("1 article", contents.elements("p").single { it.getAttribute("class") == "totals" }.textContent)
    }

    @Test
    fun theKickerNamesTheSectionAndTheNextLinkTheSourceAndTime() {
        val epub = write(
            doc(
                EditionSection("Long reads", listOf(article(source = "Aeon", minutes = 12.0), article(title = "Second", source = "Quanta", minutes = 9.0))),
                EditionSection(null, listOf(article(source = "Loose"))),
            ),
        )
        val pages = epub.articleHrefs().map { epub.xml("OEBPS/$it").elements("p").associateBy { p -> p.getAttribute("class") } }
        assertEquals("Long reads · Aeon", pages[0].getValue("kicker").textContent)
        assertEquals("Loose", pages[2].getValue("kicker").textContent)
        assertEquals("Next: Second · Quanta · 9 min", pages[0].getValue("article-nav").textContent)
        assertEquals("Second", pages[0].getValue("article-nav").elements("a").single().textContent)
    }

    /** A long read ends with a pause naming the next; after a short one, turning the page is enough. */
    @Test
    fun onlyALongReadIsFollowedByTheNextArticlesName() {
        val epub = write(
            unsectioned(
                article(title = "Short", minutes = EpubWriter.NEXT_AFTER_MINUTES - 0.5),
                article(title = "Long", minutes = EpubWriter.NEXT_AFTER_MINUTES),
                article(title = "After"),
            ),
        )
        val classes = epub.articleHrefs().map { href -> epub.xml("OEBPS/$href").elements("p").map { it.getAttribute("class") } }
        assertFalse("article-nav" in classes[0])
        assertTrue("article-nav" in classes[1])
    }

    /** The closing page is in the contents, so the wrap-up can be reached from it. */
    @Test
    fun theClosingPageIsAChapterAndABackmatterLandmark() {
        val epub = write(unsectioned(article(title = "Only")))
        assertEquals("end.xhtml", epub.navHrefs().last())
        val landmarks = epub.xml("OEBPS/nav.xhtml").elements("nav").single { it.getAttributeNS(EPUB_NS, "type") == "landmarks" }
        assertTrue(landmarks.elements("a").any { it.getAttributeNS(EPUB_NS, "type") == "backmatter" && it.getAttribute("href") == "end.xhtml" })
    }

    @Test
    fun theClosingPageSumsUpAndAsksTheReflectionOnlyWhenGiven() {
        val articles = listOf("A", "B", "C", "D", "E").map { article(source = it, minutes = 2.0) }
        val plain = write(doc(EditionSection(null, articles)))
        val end = plain.text("OEBPS/end.xhtml")
        assertTrue(end.contains("5 articles · 10 min, from A, B, C and 2 more"))
        assertFalse(end.contains("reflection"))

        val asked = write(doc(EditionSection(null, articles.take(2))).copy(reflection = "What stayed with you?"))
        val text = asked.xml("OEBPS/end.xhtml").documentElement.textContent
        assertTrue(text.contains("2 articles · 4 min, from A and B"))
        assertTrue(text.contains("What stayed with you?"))
    }

    @Test
    fun anArticlesOwnClassesDoNotReachTheBook() {
        val epub = write(unsectioned(article(body = "<p class=\"kicker\">Site kicker</p><p class=\"byline\">Site byline</p>")))
        val page = epub.xml("OEBPS/" + epub.articleHrefs().single())
        val body = page.documentElement.elements("div").single { it.getAttribute("class") == "article-body" }
        assertEquals(2, body.elements("p").size)
        assertTrue(body.elements("p").none { it.hasAttribute("class") })
    }

    @Test
    fun theStylesheetUsesOnlySelectorsKindleKeeps() {
        val css = write(unsectioned(article())).text("OEBPS/style.css")
        // Kindle's conversion drops a rule whose selector has a child or sibling combinator.
        val selectors = Regex("([^{}]+)\\{").findAll(css).map { it.groupValues[1] }.toList()
        assertTrue(selectors.isNotEmpty())
        assertTrue(selectors.filter { Regex("[>+~]|::?(before|after|first-child)").containsMatchIn(it) }.toString(), selectors.none { Regex("[>+~]|::?(before|after|first-child)").containsMatchIn(it) })
    }

    @Test
    fun chapterFileNamesUseAsciiDigitsWhateverThePhonesLanguage() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fa-IR"))
        try {
            assertEquals("article-001.xhtml", EpubWriter.articleHref(0))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun anInternationalSitesOriginalLinkIsKeptAndNamedInItsOwnLetters() {
        val epub = write(unsectioned(article(url = "https://bücher.example/buch")))
        val page = epub.text("OEBPS/article-001.xhtml")
        assertTrue(page, page.contains("href=\"https://xn--bcher-kva.example/buch\""))
        assertTrue(page, page.contains(">bücher.example</a>"))
    }
}
