package com.app.newspaperss.core.extract

import com.app.newspaperss.core.ReadingTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URISyntaxException

data class CleanResult(
    /** An XHTML-serialized fragment (`<br />`, `<img ... />`) ready to go into an EPUB chapter. */
    val html: String,
    val wordCount: Int,
    /** Absolute http(s) URLs of the images left in [html], in document order, without duplicates. */
    val imageUrls: List<String>,
    /**
     * It ended in a lone "Read more" link back to its own page, as a paid post's opening does in
     * Substack's feed. The link itself is removed.
     */
    val teaser: Boolean = false,
)

/**
 * Strips article HTML down to what reads well on e-ink: paragraphs,
 * headings, lists, quotes, images and the occasional data table. Web pages
 * bring layout divs, lazy-loaded images, share buttons and newsletter
 * sign-ups; this removes them, fixes images and links so they work outside
 * the site, and unwraps any tag it doesn't know so its text survives.
 */
object HtmlCleaner {

    /**
     * @param html a fragment or whole document; plain text is split into paragraphs.
     * @param baseUrl where the HTML came from, for making links and images absolute.
     * @param title the article's title: a heading repeating it at the very start is removed.
     */
    fun clean(html: String, baseUrl: String, title: String? = null): CleanResult {
        if (html.isBlank()) return CleanResult("", 0, emptyList())
        val source = if (HTML_TAG.containsMatchIn(html)) html else textToHtml(html)

        val doc = Jsoup.parse(source, baseUrl)
        val body = doc.body()
        removeComments(body)
        keepVideosAsLinks(body)
        body.select(REMOVE_TAGS.joinToString(",")).remove()
        removeHidden(body)
        expandSubstackNotes(body)
        removeScreenReaderOnly(body)
        removeJunk(body)
        removeRelatedLinks(body)
        removeBoilerplate(body)
        fixPictures(body)
        val titleTexts = fixImages(body, baseUrl)
        body.select("source").remove()
        flattenLayoutTables(body)
        stripTagsAndAttributes(body)
        fixLinks(body, baseUrl)
        if (!title.isNullOrBlank()) removeLeadingTitle(body, title)
        val teaser = removeReadMore(body, baseUrl)
        normalizeHeadings(body)
        removeEmpty(body)
        unwrapCaptionsWithoutMedia(body)
        collapseBlankLines(body)
        demoteStrayCaptions(body)
        // Counted before the captions go in: hover text isn't article text, and a comic's feed item
        // would otherwise read as a short text post to the rules that tell the two apart.
        val wordCount = countWords(body)
        addTitleCaptions(body, titleTexts)

        doc.outputSettings()
            .prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
        return CleanResult(
            html = body.html().trim(),
            wordCount = wordCount,
            imageUrls = body.select("img").map { it.attr("src") }.distinct(),
            teaser = teaser,
        )
    }

    /** Wraps plain text in paragraphs, taking blank lines as paragraph breaks. */
    fun textToHtml(text: String): String = text.split(BLANK_LINE)
        .map { it.trim().replace(WHITESPACE, " ") }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${Entities.escape(it)}</p>" }

    internal fun countWords(element: Element): Int = ReadingTime.words(element.text())

    internal fun countWords(html: String): Int = countWords(Jsoup.parse(html).body())

    /**
     * Picks a srcset candidate big enough for an e-reader screen without
     * being huge: images are re-encoded to at most 1200px later, so a 4000px
     * original only costs download time.
     */
    internal fun bestSrcsetCandidate(srcset: String): String? {
        val candidates = srcset.trim().split(SRCSET_SEPARATOR).mapNotNull { candidate ->
            val parts = candidate.trim().split(WHITESPACE)
            val url = parts.firstOrNull()?.trimEnd(',')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val descriptor = parts.getOrNull(1)?.let { DESCRIPTOR.matchEntire(it) }
            SrcsetCandidate(url, descriptor?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0, descriptor?.groupValues?.get(2) ?: "")
        }
        if (candidates.isEmpty()) return null
        val widths = candidates.filter { it.unit == "w" }
        if (widths.isNotEmpty()) {
            return (widths.filter { it.value >= PREFERRED_IMAGE_WIDTH }.minByOrNull { it.value }
                ?: widths.maxBy { it.value }).url
        }
        // Past 2x the image is far bigger than any e-reader needs.
        return (candidates.filter { it.value <= 2 }.maxByOrNull { it.value } ?: candidates.first()).url
    }

    /**
     * Makes [url] absolute against [baseUrl] and percent-encodes characters
     * URLs can't contain (spaces, braces, a second '#'), which EPUB checkers
     * and Send to Kindle reject. Returns null for anything that isn't an
     * absolute URL with one of [schemes], including mangled markup.
     */
    internal fun absoluteUrl(url: String, baseUrl: String, schemes: Set<String> = WEB_SCHEMES): String? {
        val reference = encodeUrl(url) ?: return null
        return try {
            val base = encodeUrl(baseUrl)?.let { URI(it) }?.takeIf { it.isAbsolute }
            val resolved = when {
                base == null -> URI(reference)
                // URI.resolve against "https://host" (no path) would glue the reference onto the host name.
                base.rawPath.isNullOrEmpty() && !base.isOpaque -> base.resolve("/").resolve(reference)
                else -> base.resolve(reference)
            }
            val scheme = resolved.scheme?.lowercase() ?: return null
            if (scheme !in schemes) return null
            if (scheme != "mailto" && resolved.host.isNullOrEmpty()) return null
            resolved.toString()
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty() || MANGLED_URL.containsMatchIn(url)) return null
        val hash = url.indexOf('#')
        if (hash < 0) return percentEncode(url)
        return percentEncode(url.substring(0, hash)) + "#" + percentEncode(url.substring(hash + 1).replace("#", "%23"))
    }

    private fun percentEncode(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && isHex(s.getOrNull(i + 1)) && isHex(s.getOrNull(i + 2)) -> out.append(c)
                c.code < 128 && c in URL_SAFE -> out.append(c)
                else -> {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
                    for (b in s.substring(i, end).toByteArray(Charsets.UTF_8)) {
                        out.append('%').append("%02X".format(b.toInt() and 0xFF))
                    }
                    i = end
                    continue
                }
            }
            i++
        }
        return out.toString()
    }

    private fun isHex(c: Char?) = c != null && (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')

    private fun removeComments(root: Element) {
        val comments = mutableListOf<Node>()
        root.traverse { node, _ -> if (node is Comment) comments += node }
        comments.forEach { it.remove() }
    }

    /**
     * Elements removed by an earlier iteration keep their own parent links, so
     * "is it still in the document" means its root is still the document.
     */
    private fun Element.isAttached() = ownerDocument() != null

    private fun removeHidden(body: Element) {
        val total = countWords(body).coerceAtLeast(1)
        for (el in body.select("*")) {
            if (el === body || !el.isAttached()) continue
            val style = el.attr("style").replace(" ", "").lowercase()
            if (el.hasAttr("hidden") || "display:none" in style || "visibility:hidden" in style) {
                el.remove()
            } else if (el.attr("aria-hidden") == "true" && isSmallPart(el, total)) {
                // Paywall scripts mark the whole article body aria-hidden, so only small parts go.
                el.remove()
            }
        }
    }

    /**
     * Removes text meant only for screen readers ("list 1 of 4", "Skip to content"), which
     * sites hide with CSS the EPUB doesn't carry. Readability drops class names, so page
     * extraction calls this before running it.
     */
    internal fun removeScreenReaderOnly(root: Element) {
        for (el in root.select("*")) {
            if (el === root || !el.isAttached()) continue
            // By length, not share of the page: before Readability the page includes navigation and
            // comments, and a paywalled article body can sit in one of these classes.
            if (el.classNames().any { it.lowercase() in SCREEN_READER_ONLY } && countWords(el) <= SCREEN_READER_MAX_WORDS &&
                el.selectFirst("img") == null && !isOnlyLabel(el)
            ) {
                el.remove()
            }
        }
    }

    /**
     * Whether [el] is part of all the text a link or button has, as on an icon link: without its
     * screen-reader text, the link would be empty.
     */
    private fun isOnlyLabel(el: Element): Boolean {
        val control = el.parents().firstOrNull { it.tagName() == "a" || it.tagName() == "button" } ?: return false
        val visible = control.clone()
        visible.select("*").filter { e -> e.classNames().any { it.lowercase() in SCREEN_READER_ONLY } }.forEach { it.remove() }
        return visible.text().isBlank()
    }

    /**
     * Removes a "Recommended stories" style box of links. Readability often flattens the box, so this
     * goes by the heading and the list right after it rather than a class; an extractor can also drop
     * the list and leave the heading over the article's next paragraph, so such a heading goes on its
     * own too. A list long enough to be a real part of the article stays, with its heading.
     */
    private fun removeRelatedLinks(body: Element) {
        val total = countWords(body).coerceAtLeast(1)
        for (heading in body.select("h2, h3, h4, h5, h6")) {
            val text = heading.text().trim()
            if (!heading.isAttached() || !FURNITURE_HEADING.matches(text)) continue
            val box = heading.parent()
            if (box != null && box !== body && heading === box.firstElementChild() && box.children().drop(1).all(::isLinkList) &&
                box.childrenSize() > 1 && isSmallPart(box, total)
            ) {
                box.remove()
                continue
            }
            // The next node, not the next element: text between the heading and a list belongs to the heading.
            var following = heading.nextSibling()
            while (following is TextNode && following.isBlank) following = following.nextSibling()
            if (following is Element && isLinkList(following)) {
                // An essay's own reading list can be long; a site's box of links is short.
                if (isSmallPart(following, total)) {
                    following.remove()
                    heading.remove()
                }
            } else if (LONE_FURNITURE_HEADING.matches(text) && !(following is Element && following.tagName() in CONTENT_TAGS)) {
                heading.remove()
            }
        }
    }

    /** A list whose items are all, or nearly all, link text. */
    private fun isLinkList(el: Element): Boolean {
        if (el.tagName() !in LIST_TAGS) return false
        val items = el.children().filter { it.tagName() == "li" }
        return items.isNotEmpty() && items.all { li ->
            val text = li.text()
            text.isNotBlank() && li.select("a").text().length >= LINK_TEXT_SHARE * text.length
        }
    }

    private fun removeJunk(body: Element) {
        val total = countWords(body).coerceAtLeast(1)
        for (el in body.select("*")) {
            if (el === body || el.tagName() == "img" || !el.isAttached()) continue
            val isJunk = el.attr("role") in JUNK_ROLES || classAndIdTokens(el).any { it in JUNK_TOKENS }
            if (isJunk && isSmallPart(el, total)) el.remove()
        }
    }

    /** Safety net: an unlucky class name must never take most of the article with it. */
    internal fun isSmallPart(el: Element, totalWords: Int) = countWords(el).toDouble() / totalWords < SAFETY_NET_SHARE

    private fun classAndIdTokens(el: Element): List<String> =
        "${el.className()} ${el.id()}".lowercase().split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }

    private fun removeBoilerplate(body: Element) {
        for (el in body.select("p, div, li, span, h2, h3, h4, h5, h6")) {
            if (!el.isAttached()) continue
            val text = el.text().trim()
            val words = text.split(WHITESPACE).size
            if (words <= BOILERPLATE_MAX_WORDS && BOILERPLATE.containsMatchIn(text) ||
                words <= PITCH_MAX_WORDS && SUBSCRIBE_PITCH.containsMatchIn(text)
            ) el.remove()
        }
    }

    /**
     * An embedded video can't play on an e-reader, and without it its caption would describe
     * nothing. A video with a poster frame becomes that picture; a YouTube or Vimeo player becomes
     * a link to the video, with YouTube's thumbnail. Other players go, as before. tt-rss passes no
     * iframes on at all, which [unwrapCaptionsWithoutMedia] deals with.
     */
    private fun keepVideosAsLinks(body: Element) {
        for (video in body.select("video[poster]")) {
            video.replaceWith(Element("img").attr("src", video.attr("poster")).attr("alt", "Video"))
        }
        for (frame in body.select("iframe[src]")) {
            val src = frame.absUrl("src").ifEmpty { frame.attr("src") }
            val title = frame.attr("title").trim()
            val youtube = YOUTUBE_EMBED.find(src)?.groupValues?.get(1)
            val vimeo = VIMEO_EMBED.find(src)?.groupValues?.get(1)
            val (watch, label) = when {
                youtube != null -> "https://www.youtube.com/watch?v=$youtube" to "YouTube"
                vimeo != null -> "https://vimeo.com/$vimeo" to "Vimeo"
                else -> continue
            }
            val link = Element("a").attr("href", watch)
            if (youtube != null) link.appendElement("img").attr("src", "https://i.ytimg.com/vi/$youtube/hqdefault.jpg").attr("alt", title.ifEmpty { "Video" })
            val text = Element("p").appendChild(Element("a").attr("href", watch).text(if (title.isEmpty()) "Watch on $label" else "Watch on $label: $title"))
            frame.replaceWith(link)
            link.after(text)
        }
    }

    /**
     * Removes a final link back to the article's own page that only says "Read more": Substack ends
     * a paid post's opening with one in its feed, and the book already links to the original.
     *
     * @return whether there was one.
     */
    private fun removeReadMore(body: Element, baseUrl: String): Boolean {
        var last: Element? = body.children().lastOrNull() ?: return false
        while (last != null && last.childrenSize() == 1 && last.tagName() != "a" && last.ownText().isBlank()) last = last.child(0)
        if (last == null || last.tagName() != "a" || !READ_MORE.matches(last.text().trim())) return false
        if (comparable(last.absUrl("href").ifEmpty { last.attr("href") }) != comparable(baseUrl)) return false
        var gone: Element = last
        while (gone.parent() !== body && gone.parent()?.text()?.trim() == last.text().trim()) gone = gone.parent()!!
        gone.remove()
        return true
    }

    /**
     * A figure left with only its caption (its video or embed couldn't come along) would caption
     * nothing: the caption becomes a plain paragraph.
     */
    private fun unwrapCaptionsWithoutMedia(body: Element) {
        for (figure in body.select("figure")) {
            if (figure.selectFirst("img, table, pre, blockquote") != null) continue
            val caption = figure.selectFirst("figcaption") ?: continue
            if (caption.select("p").isEmpty()) caption.tagName("p") else caption.unwrap()
            figure.unwrap()
        }
    }

    private fun isPlaceholder(src: String) = src.startsWith("data:") && src.length < 1000

    private fun imageSource(img: Element): String? {
        for (attribute in listOf("data-srcset", "srcset")) {
            val best = img.attr(attribute).takeIf { it.isNotBlank() }?.let { bestSrcsetCandidate(it) }
            if (best != null && !isPlaceholder(best)) return best
        }
        return (LAZY_IMAGE_ATTRIBUTES + "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !isPlaceholder(it) }
    }

    private fun fixPictures(body: Element) {
        for (picture in body.select("picture")) {
            val img = picture.selectFirst("img") ?: Element("img")
            if (imageSource(img) == null) {
                // JPEG and PNG before WebP/AVIF: fewer conversions, and some readers choke on the rest.
                val srcset = picture.select("source")
                    .sortedBy { s -> if (MODERN_FORMATS.any { it in s.attr("type") }) 1 else 0 }
                    .map { it.attr("srcset").ifBlank { it.attr("data-srcset") } }
                    .firstOrNull { it.isNotBlank() }
                if (srcset != null) img.attr("srcset", srcset)
            }
            picture.replaceWith(img)
        }
    }

    /** Returns the images whose `title` is worth keeping (see [titleText]), with that title. */
    private fun fixImages(body: Element, baseUrl: String): Map<Element, String> {
        val titles = LinkedHashMap<Element, String>()
        for (img in body.select("img")) {
            val isTrackingPixel = listOf("width", "height").any { dim ->
                img.attr(dim).trim().removeSuffix("px").toIntOrNull()?.let { it <= 2 } == true
            }
            val src = if (isTrackingPixel) null else imageSource(img)?.let { absoluteUrl(it, baseUrl) }?.takeUnless { TRACKER.containsMatchIn(it) }
            if (src == null) {
                img.remove()
                continue
            }
            val alt = img.attr("alt").trim()
            titleText(img.attr("title"), alt, src)?.let { titles[img] = it }
            img.clearAttributes()
            img.attr("src", src).attr("alt", alt)
        }
        return titles
    }

    /**
     * An image's `title` as a caption: a webcomic's hover text (xkcd's second joke). Not when it
     * only repeats the alt text or names the file, as CMSs fill it in by default.
     */
    private fun titleText(title: String, alt: String, src: String): String? {
        val text = title.replace(WHITESPACE, " ").trim()
        if (text.isEmpty() || text.equals(alt, ignoreCase = true)) return null
        val file = src.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val fileWords = file.substringBeforeLast('.').replace(FILE_NAME_SEPARATORS, " ").trim()
        if (text.equals(file, ignoreCase = true) || text.replace(FILE_NAME_SEPARATORS, " ").equals(fileWords, ignoreCase = true)) return null
        return text
    }

    /**
     * Puts each image's title text in a `<figcaption>` under it: in its own figure if it has one
     * without a caption, else in a new figure around the image (and the link or paragraph holding
     * only it). An image inside a line of text is left alone, since a figure can't go there, and
     * so is text the article already shows, such as a caption repeating the hover text or the
     * same title on an earlier image.
     */
    private fun addTitleCaptions(body: Element, titles: Map<Element, String>) {
        if (titles.isEmpty()) return
        var shown = body.text().replace(WHITESPACE, " ").lowercase()
        for ((img, title) in titles) {
            if (img.root() !== body.root() || title.lowercase() in shown) continue
            var block: Element = img
            while (true) {
                val parent = block.parent() ?: break
                if (parent === body || parent.tagName() !in SOLE_IMAGE_WRAPPERS || parent.text().isNotBlank()) break
                if (parent.childNodes().any { it !== block && !(it is TextNode && it.isBlank) }) break
                block = parent
            }
            val parent = block.parent() ?: continue
            val figure = when {
                parent.tagName() == "figure" ->
                    parent.takeIf { it.select("img").size == 1 && it.children().none { c -> c.tagName() == "figcaption" } }
                (parent === body || parent.tagName() in FIGURE_PARENTS) && onItsOwnLine(block) ->
                    Element("figure").also { block.replaceWith(it); it.appendChild(block) }
                else -> null
            } ?: continue
            figure.appendElement("figcaption").text(title)
            shown += " " + title.lowercase()
        }
    }

    /** Nothing but blocks, or nothing at all, either side of [element]: it isn't part of a line of text. */
    private fun onItsOwnLine(element: Element): Boolean {
        fun isBlock(node: Node?): Boolean = node == null || (node is Element && node.tagName() in BLOCK_TAGS)
        fun neighbour(step: (Node) -> Node?): Node? {
            var node = step(element)
            while (node is TextNode && node.isBlank) node = step(node)
            return node
        }
        return isBlock(neighbour { it.previousSibling() }) && isBlock(neighbour { it.nextSibling() })
    }

    private fun isLayoutTable(table: Element): Boolean {
        if (table.attr("role") == "presentation" || table.selectFirst("table table") != null) return true
        val rows = table.select("tr")
        if (rows.isNotEmpty() && rows.all { row -> row.children().count { it.tagName() in CELL_TAGS } <= 1 }) return true
        return table.select("td, th").any { countWords(it) > LAYOUT_TABLE_CELL_WORDS }
    }

    /** Newsletters lay out whole pages with tables, which e-readers render as cramped grids. */
    private fun flattenLayoutTables(body: Element) {
        for (table in body.select("table")) {
            if (!table.isAttached() || table.parents().any { it.tagName() == "table" } || !isLayoutTable(table)) continue
            for (el in table.select("table, thead, tbody, tfoot, tr, td, th, caption")) {
                el.tagName("div")
                el.clearAttributes()
            }
        }
    }

    private fun stripTagsAndAttributes(body: Element) {
        for (el in body.select("*")) {
            if (el === body) continue
            val tag = el.tagName()
            if (tag !in ALLOWED_TAGS) {
                el.unwrap()
                continue
            }
            val allowed = ALLOWED_ATTRIBUTES[tag].orEmpty() + "id"
            el.attributes().asList().map { it.key }.filter { it !in allowed }.forEach { el.removeAttr(it) }
        }
    }

    /**
     * Substack embeds a Note as an empty element its page script fills in from `data-attrs`. Read
     * without the script, it's an empty div that goes (its class, "comment", reads as a comments
     * box, and tt-rss strips classes anyway), leaving the sentence that introduced it hanging. Its
     * text and author make a quote instead.
     */
    private fun expandSubstackNotes(body: Element) {
        // The last quote placed after each paragraph, so several from one paragraph keep their order.
        val placedAfter = mutableMapOf<Element, Element>()
        for (el in body.select("[data-attrs]")) {
            if (!el.isAttached()) continue
            val comment = runCatching { Json.parseToJsonElement(el.attr("data-attrs")) as? JsonObject }.getOrNull()?.get("comment") as? JsonObject ?: continue
            val text = (comment["body"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val quote = Element("blockquote")
            text.split(NEWLINES).map { it.trim() }.filter { it.isNotEmpty() }.forEach { quote.appendElement("p").text(it) }
            (comment["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { quote.appendElement("p").text("— $it") }
            // A quote can't sit inside a paragraph in XHTML: one embedded in one goes after it.
            val paragraph = el.parents().firstOrNull { it.tagName() == "p" }
            if (paragraph != null) {
                el.remove()
                (placedAfter[paragraph] ?: paragraph).after(quote)
                placedAfter[paragraph] = quote
            } else {
                el.replaceWith(quote)
            }
        }
    }

    /**
     * Gets footnote links working again after tt-rss, which strips ids and resolves "#footnote-4"
     * against the site's address (which may have a path, like a blog's). A footnote's missing target
     * is found from its other link, the one with the same number whose fragment is named alike
     * ("footnote-4" and "footnote-anchor-4", "fn1" and "fnref1"), which links back; only short link
     * texts count, as footnote markers are. A link to this page or its site plus a fragment then
     * becomes an in-book link, but only when something in the book has that id: otherwise it may be
     * a real link to the site's front page, and it stays one.
     */
    private fun repairFragmentLinks(body: Element, baseUrl: String) {
        val page = comparable(baseUrl.substringBefore('#'))
        fun fragmentOf(link: Element): String? {
            val href = link.attr("href").trim()
            val hash = href.indexOf('#')
            if (hash < 0 || hash == href.length - 1) return null
            val address = comparable(href.substring(0, hash))
            return if (hash == 0 || page.startsWith(address)) href.substring(hash + 1) else null
        }
        val links = body.select("a[href]").mapNotNull { link -> fragmentOf(link)?.let { link to it } }
        val ids = body.select("[id]").map { it.id() }.toMutableSet()
        val markers = links.filter { (link, _) -> link.text().trim().length <= FOOTNOTE_MARKER_MAX }
        val byNumber = markers.groupBy { (_, fragment) -> TRAILING_NUMBER.find(fragment)?.value }
        for ((link, target) in markers) {
            if (target in ids) continue
            val number = TRAILING_NUMBER.find(target)?.value ?: continue
            val stem = target.removeSuffix(number)
            val back = byNumber[number].orEmpty().singleOrNull { (other, fragment) ->
                val otherStem = fragment.removeSuffix(number)
                other !== link && !other.hasAttr("id") && otherStem != stem && otherStem.commonPrefixWith(stem).length >= STEM_PREFIX_MIN
            } ?: continue
            back.first.id(target)
            ids += target
        }
        for ((link, fragment) in links) if (fragment in ids) link.attr("href", "#$fragment")
    }

    /**
     * An address with what tt-rss and sites vary dropped: the scheme, "www.", a query and a trailing
     * slash. Ending in "/", so "blog/" isn't taken as the start of "blog-2/".
     */
    private fun comparable(url: String): String =
        url.trim().substringAfter("://").removePrefix("www.").substringBefore('?').trimEnd('/') + "/"

    private fun fixLinks(body: Element, baseUrl: String) {
        repairFragmentLinks(body, baseUrl)
        val ids = mutableMapOf<String, String>()
        for (el in body.select("[id]")) {
            val old = el.id()
            var new = old.replace(INVALID_ID_CHARS, "-")
            if (new.firstOrNull()?.isLetter() != true) new = "id-$new"
            // Duplicate ids make the XHTML invalid.
            if (old in ids || new in ids.values) {
                el.removeAttr("id")
                continue
            }
            ids[old] = new
            el.attr("id", new)
        }
        for (link in body.select("a")) {
            val href = link.attr("href").trim()
            val target = when {
                href.isEmpty() -> null
                href.startsWith("#") -> ids[href.substring(1)]?.let { "#$it" }
                else -> absoluteUrl(href, baseUrl, LINK_SCHEMES)
            }
            if (target == null) link.unwrap() else link.attr("href", target)
        }
    }

    private fun normalizedText(text: String) = text.lowercase().replace(NON_WORD, " ").trim()

    /** Renderers print the title themselves, so a heading repeating it at the very start is a duplicate. */
    private fun removeLeadingTitle(body: Element, title: String) {
        val heading = body.selectFirst("h1, h2, h3") ?: return
        val all = body.select("*")
        val headingIndex = all.indexOf(heading)
        val textBefore = all.take(headingIndex)
            .filter { it.tagName() in setOf("p", "li", "blockquote") }
            .any { it.text().isNotBlank() }
        if (!textBefore && normalizedText(heading.text()) == normalizedText(title)) heading.remove()
    }

    /** The article title is the chapter's only h1, so content headings shift down to start at h2. */
    private fun normalizeHeadings(body: Element) {
        val headings = body.select("h1, h2, h3, h4, h5, h6")
        val top = headings.minOfOrNull { it.tagName()[1].digitToInt() } ?: return
        val shift = 2 - top
        if (shift <= 0) return
        for (h in headings) h.tagName("h${minOf(6, h.tagName()[1].digitToInt() + shift)}")
    }

    private fun removeEmpty(body: Element) {
        for (el in body.select("*").reversed()) {
            if (el === body || el.tagName() !in EMPTY_REMOVABLE_TAGS) continue
            if (el.text().isNotBlank() || el.selectFirst("img, hr, table") != null) continue
            if (el.tagName() == "a" && el.hasAttr("id")) continue
            el.remove()
        }
    }

    /**
     * A figcaption is only valid as the first or last child of a figure, and
     * one per figure; epubcheck rejects the book otherwise. Sites nest
     * captions in layout divs, which unwrapping or removing can't always
     * fix, so a caption anywhere else becomes a plain div.
     */
    private fun demoteStrayCaptions(body: Element) {
        for (caption in body.select("figcaption")) {
            val figure = caption.parent()
            // Nodes, not elements: a credit span unwrapped to bare text beside the caption counts too.
            val content = figure?.childNodes()?.filterNot { it is TextNode && it.isBlank }.orEmpty()
            val valid = figure != null && figure.tagName() == "figure" &&
                (caption === content.first() || caption === content.last()) &&
                figure.children().count { it.tagName() == "figcaption" } == 1
            if (!valid) caption.tagName("div")
        }
    }

    private fun collapseBlankLines(body: Element) {
        // Removed elements leave neighbouring whitespace behind as separate text nodes; merge them first.
        for (el in body.select("*")) {
            for (node in el.childNodes().toList()) {
                val previous = node.previousSibling()
                if (node is TextNode && previous is TextNode) {
                    previous.text(previous.wholeText + node.wholeText)
                    node.remove()
                }
            }
        }
        val blanks = mutableListOf<TextNode>()
        body.traverse { node, _ ->
            if (node is TextNode && node.isBlank && '\n' in node.wholeText &&
                (node.parent() as? Element)?.closest("pre") == null
            ) blanks += node
        }
        blanks.forEach { it.text("\n") }
    }

    private data class SrcsetCandidate(val url: String, val value: Double, val unit: String)

    private const val SAFETY_NET_SHARE = 0.4
    private const val PREFERRED_IMAGE_WIDTH = 1000
    private const val LAYOUT_TABLE_CELL_WORDS = 80
    private const val BOILERPLATE_MAX_WORDS = 12
    private const val PITCH_MAX_WORDS = 40

    private val REMOVE_TAGS = setOf(
        "script", "style", "noscript", "iframe", "object", "embed", "applet", "form", "input", "button",
        "select", "textarea", "label", "nav", "aside", "footer", "svg", "canvas", "video", "audio", "track",
        "template", "link", "meta", "title", "base", "dialog", "menu", "map", "area",
    )

    // div stays: unwrapping it would run neighbouring blocks (and flattened table cells) together.
    private val ALLOWED_TAGS = setOf(
        "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "div",
        "em", "i", "strong", "b", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
        "code", "pre", "kbd", "samp", "var", "blockquote", "q", "cite", "abbr",
        "a", "ul", "ol", "li", "dl", "dt", "dd", "figure", "figcaption", "img",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
    )

    private val ALLOWED_ATTRIBUTES = mapOf(
        "a" to setOf("href"),
        "img" to setOf("src", "alt"),
        "td" to setOf("colspan", "rowspan"),
        "th" to setOf("colspan", "rowspan"),
        "ol" to setOf("start"),
    )

    // Not "paywall": some sites put it on the containers of the article's own paragraphs.
    private val JUNK_TOKENS = setOf(
        "ad", "ads", "advert", "advertisement", "adsbygoogle", "promo", "promotion", "newsletter", "subscribe",
        "subscription", "signup", "share", "sharing", "social", "related", "recommended", "recommendations",
        "comments", "comment", "sidebar", "popup", "modal", "cookie", "cookies", "banner", "sponsored",
        "outbrain", "taboola", "breadcrumb", "breadcrumbs", "toolbar",
    )
    private val SCREEN_READER_ONLY = setOf(
        "screen-reader-text", "screen-reader-only", "sr-only", "sr-text", "visually-hidden", "visuallyhidden", "a11y-hidden",
    )
    // Headings that name a site's box of links, never a section of the article itself. "Related" and
    // "Further reading" aren't here: authors use them for their own references.
    private val FURNITURE_HEADING = Regex(
        "recommended( (stories|articles|reading|for you))?|read (next|more)|you (may|might) also like|" +
            "most (read|popular)|more( (stories|picks|posts|articles|essays|reads))? (on|from) .{1,40}|" +
            "related (stories|articles|coverage|posts|content)",
        RegexOption.IGNORE_CASE,
    )
    // The ones no article uses for a section of its own paragraphs ("More on the method" can be), so they
    // go even when the extractor dropped their links and left them over the article's next paragraph.
    private val LONE_FURNITURE_HEADING = Regex(
        "recommended( (stories|articles|reading|for you))?|read next|you (may|might) also like|most (read|popular)",
        RegexOption.IGNORE_CASE,
    )
    private val CONTENT_TAGS = setOf("ul", "ol", "dl", "table")
    private const val LINK_TEXT_SHARE = 0.8
    private const val SCREEN_READER_MAX_WORDS = 12
    private val LIST_TAGS = setOf("ul", "ol")
    private val JUNK_ROLES = setOf("navigation", "complementary", "banner", "contentinfo", "dialog")

    private val LAZY_IMAGE_ATTRIBUTES = listOf(
        "data-src", "data-original", "data-lazy-src", "data-hi-res-src", "data-full-src", "data-url",
    )
    private val MODERN_FORMATS = listOf("webp", "avif")

    private val EMPTY_REMOVABLE_TAGS = setOf(
        "p", "div", "li", "ul", "ol", "dl", "h1", "h2", "h3", "h4", "h5", "h6", "a", "figure", "figcaption",
        "blockquote", "em", "i", "strong", "b", "u", "small", "table", "tr", "td", "th", "tbody", "thead",
    )
    private val CELL_TAGS = setOf("td", "th")
    // Wrappers that can hold just an image, and blocks a <figure> may go in.
    private val SOLE_IMAGE_WRAPPERS = setOf("a", "p", "div")
    private val FIGURE_PARENTS = setOf("div", "blockquote", "li", "dd", "td", "th")
    private val BLOCK_TAGS = setOf(
        "p", "div", "figure", "ul", "ol", "dl", "blockquote", "pre", "table", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private val FILE_NAME_SEPARATORS = Regex("[-_+\\s]+")

    private val BOILERPLATE = Regex(
        "^(listen to (this|the) (article|essay|story|episode)|\\d+[ -]min(ute)?s? (read|listen)|share (this|on)\\b|" +
            "advertisement$|sign up (for|to)\\b|subscribe (to|now|today)\\b|read more:|related:|recommended:|" +
            "click here to\\b|follow us on\\b|skip past newsletter|after newsletter promotion|(add|leave|post) a comment:?$|" +
            "newsletter$)",
        RegexOption.IGNORE_CASE,
    )

    // A newsletter's pitch for subscribing, wherever it sits in a short block: Substack's widget (its
    // class is gone after tt-rss) and the line over a paid post's paywall.
    private val SUBSCRIBE_PITCH = Regex(
        "consider becoming a (free or )?paid subscriber|is a reader-supported publication|" +
            "subscribe (for free )?to receive new posts|subscribe to .{1,60} to keep reading|" +
            "this post is for (paid |paying )?subscribers( only)?|" +
            // The label left over a newsletter's sign-up form, and a box ending in its sign-up link.
            "subscribe to [^.!?]{1,60}:$|newsletter\\b.{0,200}\\bsign up\\.?$",
        RegexOption.IGNORE_CASE,
    )
    // Feeds' view counters, known by address: tt-rss strips the width="1" that gives them away.
    private val TRACKER = Regex("medium\\.com/_/stat\\?|pixel\\.wp\\.com/|stats\\.wordpress\\.com/|feeds\\.feedburner\\.com/~r/|feedproxy\\.google\\.com/~r/")
    private val READ_MORE = Regex("(read more|continue reading|keep reading)(…|\\.\\.\\.)?", RegexOption.IGNORE_CASE)
    private val YOUTUBE_EMBED = Regex("youtube(?:-nocookie)?\\.com/embed/([A-Za-z0-9_-]{6,})")
    private val VIMEO_EMBED = Regex("player\\.vimeo\\.com/video/(\\d+)")

    private val HTML_TAG = Regex("<\\s*[a-zA-Z!/]")
    private val WHITESPACE = Regex("\\s+")
    private val BLANK_LINE = Regex("\\n\\s*\\n")
    private val NEWLINES = Regex("\\n+")
    private val TRAILING_NUMBER = Regex("\\d+$")
    private const val FOOTNOTE_MARKER_MAX = 4
    private const val STEM_PREFIX_MIN = 2
    private val TOKEN_SEPARATOR = Regex("[\\s_\\-]+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val INVALID_ID_CHARS = Regex("[^A-Za-z0-9_.\\-]")
    // A comma and whitespace, or a bare comma right after a descriptor: image CDNs put bare commas
    // inside URLs, and tt-rss joins the candidates it passes on with bare commas.
    private val SRCSET_SEPARATOR = Regex(",\\s+|(?<=\\s\\d{1,6}[wx]),|(?<=\\s\\d{1,3}\\.\\d{1,3}x),")
    private val DESCRIPTOR = Regex("([\\d.]+)([wx])")
    private val MANGLED_URL = Regex("[\"\\\\<>]")
    private val URL_SAFE = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" + "-._~:/?@!$&'()*+,;=").toSet()
    private val WEB_SCHEMES = setOf("http", "https")
    private val LINK_SCHEMES = setOf("http", "https", "mailto")
}
