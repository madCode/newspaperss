package com.app.newspaperss.core.epub

import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an [EditionDoc] as an EPUB 3 book that also carries an NCX and a `<guide>` for EPUB 2
 * readers, in the shape Send to Kindle accepts.
 *
 * The book reads: cover page, contents, one page per article, and a closing page.
 */
object EpubWriter {
    private const val MIMETYPE = "application/epub+zip"
    private const val XHTML = "application/xhtml+xml"
    private const val COVER = "cover.xhtml"
    private const val CONTENTS = "contents.xhtml"
    private const val END = "end.xhtml"
    private const val MAX_COVER_SOURCES = 4
    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif")
    private val IMAGE_HREF = Regex("images/[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")

    // The page text is English whatever the content language, so dates are formatted to match.
    private val COVER_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
    private val BYLINE_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    /**
     * Writes [doc] to [out] as a zip. [out] is not closed. Everything is validated before the
     * first byte is written, so an [IllegalArgumentException] leaves [out] untouched.
     *
     * @throws IllegalArgumentException if an image's href is not a plain path under `images/`,
     *   its media type is not JPEG, PNG or GIF, or two different images share an href.
     */
    fun write(doc: EditionDoc, out: OutputStream) {
        val book = Book(doc)
        val entries = book.entries()
        val time = doc.modified.toEpochMilli()
        val zip = ZipOutputStream(out)
        // The mimetype must be the first entry, uncompressed and without extra fields, so that
        // readers can identify the file from its first bytes.
        val mimetype = MIMETYPE.toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(
            ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
                this.time = time
            },
        )
        zip.write(mimetype)
        zip.closeEntry()
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name).apply { this.time = time })
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private class Page(val id: String, val href: String, val title: String, val xhtml: String)

    private class TocEntry(val label: String, val href: String, val children: List<TocEntry> = emptyList())

    private class Book(val doc: EditionDoc) {
        val lang = doc.language
        val articles = doc.articles
        val articleHrefs = articles.indices.map { "article-%03d.xhtml".format(it + 1) }
        val images = collectImages()
        val totalMinutes = articles.sumOf { it.minutes }

        fun articleTitle(index: Int): String = articles[index].title.trim().ifEmpty { "Article ${index + 1}" }

        fun entries(): List<Pair<String, ByteArray>> {
            val pages = listOf(coverPage(), contentsPage()) + articles.indices.map(::articlePage) + endPage()
            val toc = toc()
            val files = mutableListOf(
                "META-INF/container.xml" to CONTAINER_XML,
                "OEBPS/content.opf" to opf(pages),
                "OEBPS/nav.xhtml" to nav(toc),
                "OEBPS/toc.ncx" to ncx(toc),
                "OEBPS/style.css" to EPUB_CSS,
            ).map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) }.toMutableList()
            pages.forEach { files += "OEBPS/${it.href}" to it.xhtml.toByteArray(Charsets.UTF_8) }
            images.forEach { files += "OEBPS/${it.href}" to it.bytes }
            return files
        }

        private fun collectImages(): List<EpubImage> {
            val byHref = LinkedHashMap<String, EpubImage>()
            for (image in articles.flatMap { it.images }) {
                require(IMAGE_HREF.matches(image.href)) { "Image href must be a plain path under images/: ${image.href}" }
                require(image.mediaType in IMAGE_TYPES) { "Unsupported image type ${image.mediaType} for ${image.href}" }
                val existing = byHref.putIfAbsent(image.href, image)
                require(existing == null || existing.bytes.contentEquals(image.bytes)) {
                    "Two different images share the href ${image.href}"
                }
            }
            return byHref.values.toList()
        }

        private fun toc(): List<TocEntry> {
            val entries = mutableListOf(TocEntry("Contents", CONTENTS))
            var index = 0
            doc.sections.filter { it.articles.isNotEmpty() }.forEachIndexed { sectionIndex, section ->
                val articleEntries = section.articles.map {
                    TocEntry(articleTitle(index), articleHrefs[index]).also { index++ }
                }
                val title = section.title?.trim().orEmpty()
                if (title.isEmpty()) {
                    entries += articleEntries
                } else {
                    entries += TocEntry(title, "$CONTENTS#${sectionId(sectionIndex)}", articleEntries)
                }
            }
            return entries
        }

        private fun sectionId(index: Int) = "section-${index + 1}"

        private fun coverPage(): Page {
            val sources = articles.map { it.sourceTitle.trim() }.filter { it.isNotEmpty() }.distinct()
            val sourceLine = sources.take(MAX_COVER_SOURCES).joinToString(", ") +
                if (sources.size > MAX_COVER_SOURCES) " and more" else ""
            val body = buildString {
                append("<div class=\"cover\">\n")
                append("<p class=\"masthead\">${esc(doc.masthead)}</p>\n")
                append("<h1 class=\"edition-title\">${esc(doc.title)}</h1>\n")
                append("<p class=\"date\">${esc(COVER_DATE.format(doc.date))}</p>\n")
                append("<p class=\"totals\">${esc(totalsLine())}</p>\n")
                if (sources.isNotEmpty()) append("<p class=\"sources\">${esc(sourceLine)}</p>\n")
                append("</div>")
            }
            return Page("cover", COVER, doc.title, xhtmlPage(doc.title, lang, body))
        }

        private fun totalsLine() = "${plural(articles.size, "article")} · ${formatMinutes(totalMinutes)}"

        private fun contentsPage(): Page {
            val body = buildString {
                append("<h1>Contents</h1>\n")
                append("<p class=\"totals\">${esc(totalsLine())}</p>\n")
                var index = 0
                doc.sections.filter { it.articles.isNotEmpty() }.forEachIndexed { sectionIndex, section ->
                    val title = section.title?.trim().orEmpty()
                    if (title.isNotEmpty()) append("<h2 id=\"${sectionId(sectionIndex)}\">${esc(title)}</h2>\n")
                    append("<ol class=\"contents\" start=\"${index + 1}\">\n")
                    for (article in section.articles) {
                        val meta = listOf(article.sourceTitle.trim(), formatMinutes(article.minutes))
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                        append("<li><a href=\"${articleHrefs[index]}\">${esc(articleTitle(index))}</a>")
                        append("<br/><span class=\"meta\">${esc(meta)}</span></li>\n")
                        index++
                    }
                    append("</ol>\n")
                }
            }
            return Page("contents", CONTENTS, "Contents", xhtmlPage("Contents", lang, body))
        }

        private fun articlePage(index: Int): Page {
            val article = articles[index]
            val title = articleTitle(index)
            val byline = listOfNotNull(
                article.sourceTitle.trim().ifEmpty { null },
                article.author?.trim()?.ifEmpty { null },
                article.published?.let { BYLINE_DATE.format(it) },
                formatMinutes(article.minutes),
            ).joinToString(" · ")
            val imageHrefs = article.images.map { it.href }.toSet()
            val body = buildString {
                append("<h1 class=\"article-title\">${esc(title)}</h1>\n")
                append("<p class=\"byline\">${esc(byline)}</p>\n")
                article.note?.trim()?.takeIf { it.isNotEmpty() }?.let { append("<p class=\"note\">${esc(it)}</p>\n") }
                append("<div class=\"article-body\">")
                append(ArticleBody.toXhtml(article.bodyHtml, "a${index + 1}-", imageHrefs))
                append("</div>\n")
                val url = article.url.trim()
                if (url.isNotEmpty()) {
                    val href = externalHref(url)
                    append("<p class=\"source-link\">Original: ")
                    append(if (href == null) esc(url) else "<a href=\"${esc(href)}\">${esc(url)}</a>")
                    append("</p>\n")
                }
                if (index + 1 < articles.size) {
                    append("<p class=\"article-nav\">Next: <a href=\"${articleHrefs[index + 1]}\">")
                    append("${esc(articleTitle(index + 1))}</a></p>\n")
                }
            }
            return Page("article-${index + 1}", articleHrefs[index], title, xhtmlPage(title, lang, body))
        }

        private fun endPage(): Page {
            val body = "<p class=\"end\">That's all for today.</p>\n<p class=\"end meta\">${esc(doc.masthead)} · ${esc(COVER_DATE.format(doc.date))}</p>"
            return Page("end", END, "The end", xhtmlPage("The end", lang, body))
        }

        fun opf(pages: List<Page>): String {
            val modified = doc.modified.truncatedTo(ChronoUnit.SECONDS)
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\" xml:lang=\"${esc(lang)}\">\n")
                append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                append("<dc:identifier id=\"book-id\">${esc(doc.identifier)}</dc:identifier>\n")
                append("<dc:title>${esc(doc.title)}</dc:title>\n")
                append("<dc:language>${esc(lang)}</dc:language>\n")
                append("<dc:creator>${esc(doc.masthead)}</dc:creator>\n")
                append("<dc:date>${doc.date}</dc:date>\n")
                append("<meta property=\"dcterms:modified\">${DateTimeFormatter.ISO_INSTANT.format(modified)}</meta>\n")
                append("</metadata>\n<manifest>\n")
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"$XHTML\" properties=\"nav\"/>\n")
                append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
                append("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n")
                for (page in pages) append("<item id=\"${page.id}\" href=\"${page.href}\" media-type=\"$XHTML\"/>\n")
                images.forEachIndexed { i, image ->
                    append("<item id=\"image-${i + 1}\" href=\"${esc(image.href)}\" media-type=\"${image.mediaType}\"/>\n")
                }
                append("</manifest>\n<spine toc=\"ncx\">\n")
                // Every page is linear: Send to Kindle rejects a non-linear cover that nothing links to.
                for (page in pages) append("<itemref idref=\"${page.id}\"/>\n")
                append("</spine>\n<guide>\n")
                append("<reference type=\"cover\" title=\"Cover\" href=\"$COVER\"/>\n")
                append("<reference type=\"toc\" title=\"Contents\" href=\"$CONTENTS\"/>\n")
                append("<reference type=\"text\" title=\"Start\" href=\"${articleHrefs.firstOrNull() ?: END}\"/>\n")
                append("</guide>\n</package>\n")
            }
        }

        fun nav(toc: List<TocEntry>): String {
            fun list(entries: List<TocEntry>): String = entries.joinToString("\n", "<ol>\n", "\n</ol>") { entry ->
                val link = "<a href=\"${esc(entry.href)}\">${esc(entry.label)}</a>"
                if (entry.children.isEmpty()) "<li>$link</li>" else "<li>$link\n${list(entry.children)}\n</li>"
            }
            val body = buildString {
                append("<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n")
                append(list(toc))
                append("\n</nav>\n<nav epub:type=\"landmarks\" id=\"landmarks\" hidden=\"hidden\">\n<ol>\n")
                append("<li><a epub:type=\"cover\" href=\"$COVER\">Cover</a></li>\n")
                append("<li><a epub:type=\"toc\" href=\"$CONTENTS\">Contents</a></li>\n")
                append("<li><a epub:type=\"bodymatter\" href=\"${articleHrefs.firstOrNull() ?: END}\">Start</a></li>\n")
                append("</ol>\n</nav>")
            }
            return xhtmlPage("Contents", lang, body)
        }

        fun ncx(toc: List<TocEntry>): String {
            var playOrder = 0
            fun points(entries: List<TocEntry>): String = entries.joinToString("\n") { entry ->
                val order = ++playOrder
                val children = if (entry.children.isEmpty()) "" else "\n" + points(entry.children)
                "<navPoint id=\"nav-$order\" playOrder=\"$order\">" +
                    "<navLabel><text>${esc(entry.label)}</text></navLabel>" +
                    "<content src=\"${esc(entry.href)}\"/>$children</navPoint>"
            }
            val depth = if (toc.any { it.children.isNotEmpty() }) 2 else 1
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\" xml:lang=\"${esc(lang)}\">\n")
                append("<head>\n<meta name=\"dtb:uid\" content=\"${esc(doc.identifier)}\"/>\n")
                append("<meta name=\"dtb:depth\" content=\"$depth\"/>\n")
                append("<meta name=\"dtb:totalPageCount\" content=\"0\"/>\n<meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n</head>\n")
                append("<docTitle><text>${esc(doc.title)}</text></docTitle>\n<navMap>\n")
                append(points(toc))
                append("\n</navMap>\n</ncx>\n")
            }
        }
    }

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>
"""
}
