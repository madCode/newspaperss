package com.app.newspaperss.core.epub

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.plural
import java.io.OutputStream
import java.net.URI
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
 * The book reads: cover page, contents, one page per article, and a closing page. Kindle opens
 * it at the contents, the paper's front page.
 */
object EpubWriter {
    private const val MIMETYPE = "application/epub+zip"
    private const val XHTML = "application/xhtml+xml"
    private const val COVER = "cover.xhtml"
    private const val CONTENTS = "contents.xhtml"
    private const val END = "end.xhtml"
    private const val COVER_IMAGE_ID = "cover-image"
    private const val MAX_COVER_SOURCES = 4
    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif")
    private val IMAGE_HREF = Regex("images/[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")

    // The page text is English whatever the content language, so dates are formatted to match.
    private val COVER_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
    private val BYLINE_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    private val DATELINE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)

    /**
     * Writes [doc] to [out] as a zip. [out] is not closed. Everything is validated before the
     * first byte is written, so an [IllegalArgumentException] leaves [out] untouched.
     *
     * @throws IllegalArgumentException if an image's href is not a plain path under `images/`,
     *   its media type is not JPEG, PNG or GIF, two different images share an href, or the cover
     *   shares an href with an article image.
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
        val sections = doc.sections.filter { it.articles.isNotEmpty() }
        val articleSections = sections.flatMap { section -> section.articles.map { section.title?.trim().orEmpty() } }
        val sources = articles.map { it.sourceTitle.trim() }.filter { it.isNotEmpty() }.distinct()

        fun articleTitle(index: Int): String = articles[index].title.trim().ifEmpty { "Article ${index + 1}" }

        // An article with no words (a comic, a photo) would otherwise read "0 min".
        fun minutesLabel(minutes: Double): String? = if (minutes <= 0.0) null else ReadingTime.format(minutes)

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
            doc.cover?.let { files += "OEBPS/${it.href}" to it.bytes }
            return files
        }

        private fun collectImages(): List<EpubImage> {
            val byHref = LinkedHashMap<String, EpubImage>()
            for (image in articles.flatMap { it.images }) {
                checkImage(image)
                val existing = byHref.putIfAbsent(image.href, image)
                require(existing == null || existing.bytes.contentEquals(image.bytes)) {
                    "Two different images share the href ${image.href}"
                }
            }
            doc.cover?.let { cover ->
                checkImage(cover)
                // The cover's manifest item carries properties="cover-image"; a second item for the
                // same file would be invalid, and an article image doubling as cover is a caller bug.
                require(cover.href !in byHref) { "The cover shares the href ${cover.href} with an article image" }
            }
            return byHref.values.toList()
        }

        private fun checkImage(image: EpubImage) {
            require(IMAGE_HREF.matches(image.href)) { "Image href must be a plain path under images/: ${image.href}" }
            require(image.mediaType in IMAGE_TYPES) { "Unsupported image type ${image.mediaType} for ${image.href}" }
        }

        private fun toc(): List<TocEntry> {
            val entries = mutableListOf(TocEntry("Contents", CONTENTS))
            var index = 0
            for (section in sections) {
                val articleEntries = section.articles.map {
                    TocEntry(articleTitle(index), articleHrefs[index]).also { index++ }
                }
                val title = section.title?.trim().orEmpty()
                if (title.isEmpty()) {
                    entries += articleEntries
                } else {
                    // The section's first article, not its heading on the contents page: an entry
                    // pointing back to the contents takes the TOC out of reading order, and Kindle's
                    // "next chapter" would jump back to the front.
                    entries += TocEntry(title, articleEntries.first().href, articleEntries)
                }
            }
            return entries
        }

        private fun sectionId(index: Int) = "section-${index + 1}"

        private fun coverPage(): Page {
            val sourceLine = sources.take(MAX_COVER_SOURCES).joinToString(", ") +
                if (sources.size > MAX_COVER_SOURCES) " and more" else ""
            val date = COVER_DATE.format(doc.date)
            val cover = doc.cover
            val body = if (cover != null) {
                // The image already shows the masthead, title and date, so repeating them as text
                // would push the cover onto a second screen. The alt carries them for screen readers.
                val alt = "${doc.masthead}: ${doc.title}, $date"
                "<div class=\"cover-image\"><img src=\"${esc(cover.href)}\" alt=\"${esc(alt)}\"/></div>"
            } else {
                buildString {
                    append("<div class=\"cover\">\n")
                    append("<p class=\"masthead\">${esc(doc.masthead)}</p>\n")
                    append("<h1 class=\"edition-title\">${esc(doc.title)}</h1>\n")
                    append("<p class=\"date\">${esc(date)}</p>\n")
                    append("<p class=\"totals\">${esc(totalsLine())}</p>\n")
                    if (sources.isNotEmpty()) append("<p class=\"sources\">${esc(sourceLine)}</p>\n")
                    append("</div>")
                }
            }
            return Page("cover", COVER, doc.title, xhtmlPage(doc.title, lang, body))
        }

        private fun totalsLine() = listOfNotNull(plural(articles.size, "article"), minutesLabel(totalMinutes)).joinToString(" · ")

        private fun contentsPage(): Page {
            val body = buildString {
                append("<p class=\"dateline\">${esc(DATELINE.format(doc.date))}</p>\n")
                append("<h1 class=\"contents-title\">In this edition</h1>\n")
                append("<p class=\"totals\">${esc(totalsLine())}</p>\n")
                var index = 0
                sections.forEachIndexed { sectionIndex, section ->
                    val title = section.title?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        val minutes = minutesLabel(section.articles.sumOf { it.minutes })?.let { " <span class=\"meta\">· ${esc(it)}</span>" }.orEmpty()
                        append("<h2 class=\"section-title\" id=\"${sectionId(sectionIndex)}\">${esc(title)}$minutes</h2>\n")
                    }
                    append("<ol class=\"contents\" start=\"${index + 1}\">\n")
                    for (article in section.articles) {
                        val meta = listOfNotNull(article.sourceTitle.trim(), minutesLabel(article.minutes))
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                        // On the item, not the link, so a right-to-left entry is aligned as one.
                        append("<li${languageAttributes(article.language, lang)}><a href=\"${articleHrefs[index]}\">${esc(articleTitle(index))}</a>")
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
            val source = article.sourceTitle.trim()
            val byline = listOfNotNull(
                article.author?.trim()?.ifEmpty { null }?.let { "By $it" },
                article.published?.let { BYLINE_DATE.format(it) },
                minutesLabel(article.minutes)?.let { "$it read" },
            ).joinToString(" · ")
            // The section goes in the kicker, since the book has no section pages to say where one starts.
            val kicker = listOf(articleSections[index], source).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
            val imageHrefs = article.images.map { it.href }.toSet()
            val body = buildString {
                if (kicker.isNotEmpty()) append("<p class=\"kicker\">${esc(kicker)}</p>\n")
                val langAttrs = languageAttributes(article.language, lang)
                append("<h1 class=\"article-title\"$langAttrs>${esc(title)}</h1>\n")
                if (byline.isNotEmpty()) append("<p class=\"byline\">${esc(byline)}</p>\n")
                append("<hr class=\"rule\"/>\n")
                article.note?.trim()?.takeIf { it.isNotEmpty() }?.let { append("<p class=\"note\">${esc(it)}</p>\n") }
                append("<div class=\"article-body\"$langAttrs>")
                append(ArticleBody.toXhtml(article.bodyHtml, "a${index + 1}-", imageHrefs))
                append("</div>\n")
                append("<p class=\"end-mark\" aria-hidden=\"true\">&#9632;</p>\n")
                val url = article.url.trim()
                if (url.isNotEmpty()) {
                    val href = externalHref(url)
                    // The site's name, not the whole address: a long URL has nowhere to break and
                    // pushes the page wider than a phone.
                    val host = href?.let { runCatching { URI(it).host }.getOrNull() }?.removePrefix("www.")
                    if (href != null && !host.isNullOrEmpty()) {
                        append("<p class=\"source-link\">Read the original at <a href=\"${esc(href)}\">${esc(host)}</a></p>\n")
                    } else {
                        append("<p class=\"source-link\">Original: ${esc(url)}</p>\n")
                    }
                }
                if (index + 1 < articles.size) {
                    append("<p class=\"article-nav\">Next: <a href=\"${articleHrefs[index + 1]}\"${languageAttributes(articles[index + 1].language, lang)}>")
                    append(esc(articleTitle(index + 1)))
                    val next = articles[index + 1]
                    val meta = listOfNotNull(next.sourceTitle.trim(), minutesLabel(next.minutes)).filter { it.isNotEmpty() }
                    append("</a>")
                    if (meta.isNotEmpty()) append(" <span class=\"meta\">· ${esc(meta.joinToString(" · "))}</span>")
                    append("</p>\n")
                }
            }
            return Page("article-${index + 1}", articleHrefs[index], title, xhtmlPage(title, lang, body))
        }

        private fun endPage(): Page {
            val body = buildString {
                append("<h1 class=\"end-title\">That's all for today.</h1>\n")
                val from = when {
                    sources.size <= 1 -> sources.joinToString()
                    sources.size <= MAX_COVER_SOURCES -> sources.dropLast(1).joinToString(", ") + " and " + sources.last()
                    else -> sources.take(MAX_COVER_SOURCES - 1).joinToString(", ") + " and ${sources.size - MAX_COVER_SOURCES + 1} more"
                }
                val summary = totalsLine() + if (from.isEmpty()) "" else ", from $from"
                append("<p class=\"end-summary\">${esc(summary)}</p>\n")
                doc.reflection?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("<div class=\"reflection\">\n<p class=\"reflection-label\">To think about</p>\n<p>${esc(it)}</p>\n</div>\n")
                }
                append("<p class=\"end-imprint\">${esc(doc.masthead)} · ${esc(COVER_DATE.format(doc.date))}</p>")
            }
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
                // EPUB 2 readers and Kindle find the cover image through this meta, not the manifest property.
                if (doc.cover != null) append("<meta name=\"cover\" content=\"$COVER_IMAGE_ID\"/>\n")
                append("</metadata>\n<manifest>\n")
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"$XHTML\" properties=\"nav\"/>\n")
                append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
                append("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n")
                for (page in pages) append("<item id=\"${page.id}\" href=\"${page.href}\" media-type=\"$XHTML\"/>\n")
                images.forEachIndexed { i, image ->
                    append("<item id=\"image-${i + 1}\" href=\"${esc(image.href)}\" media-type=\"${image.mediaType}\"/>\n")
                }
                doc.cover?.let {
                    append("<item id=\"$COVER_IMAGE_ID\" href=\"${esc(it.href)}\" media-type=\"${it.mediaType}\" properties=\"cover-image\"/>\n")
                }
                append("</manifest>\n<spine toc=\"ncx\">\n")
                // Every page is linear: Send to Kindle rejects a non-linear cover that nothing links to.
                for (page in pages) append("<itemref idref=\"${page.id}\"/>\n")
                append("</spine>\n<guide>\n")
                append("<reference type=\"cover\" title=\"Cover\" href=\"$COVER\"/>\n")
                append("<reference type=\"toc\" title=\"Contents\" href=\"$CONTENTS\"/>\n")
                // Where Kindle opens the book: the contents, as a paper opens at its front page.
                append("<reference type=\"text\" title=\"Start\" href=\"$CONTENTS\"/>\n")
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
                append("<li><a epub:type=\"bodymatter\" href=\"$CONTENTS\">Start</a></li>\n")
                append("</ol>\n</nav>")
            }
            return xhtmlPage("Contents", lang, body)
        }

        fun ncx(toc: List<TocEntry>): String {
            var id = 0
            // A section and its first article point at the same page, and NCX requires entries
            // with the same target to share a playOrder.
            val playOrders = HashMap<String, Int>()
            fun points(entries: List<TocEntry>): String = entries.joinToString("\n") { entry ->
                val order = playOrders.getOrPut(entry.href) { playOrders.size + 1 }
                val children = if (entry.children.isEmpty()) "" else "\n" + points(entry.children)
                "<navPoint id=\"nav-${++id}\" playOrder=\"$order\">" +
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
