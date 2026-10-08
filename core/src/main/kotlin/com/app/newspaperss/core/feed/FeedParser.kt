package com.app.newspaperss.core.feed

import com.app.newspaperss.core.epub.esc
import com.app.newspaperss.core.extract.HtmlCleaner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI

/** Parses RSS 2.0, RSS 1.0 (RDF), Atom and JSON Feed. */
object FeedParser {
    private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val ATOM_NS = "http://www.w3.org/2005/Atom"
    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"
    private const val RSS1_NS = "http://purl.org/rss/1.0/"
    // media:content, itunes:summary and the like share local names with the
    // elements read here, so only RSS/Atom's own namespaces count.
    private val FEED_NAMESPACES = setOf("", RSS1_NS, ATOM_NS)
    // The root element, prefixed or not (<rss>, <atom:feed>, <rdf:RDF>).
    private val ROOT = Regex("<(?:[\\w-]+:)?(rss|feed|RDF)[\\s>]", RegexOption.IGNORE_CASE)
    // <!ENTITY name "value">, or a parameter entity (<!ENTITY % name …>).
    // A reference to another entity (&name;), not a character reference (&#160;).
    private val ENTITY_REF = Regex("&[A-Za-z_:]")
    private val ENTITY = Regex("<!ENTITY\\s+(%?)[^>]*?(\"[^\"]*\"|'[^']*'|>)", RegexOption.IGNORE_CASE)
    private const val MAX_PROLOG = 64 * 1024

    /**
     * @param body the response body.
     * @param feedUrl where it was fetched from; relative links resolve against it.
     * @param truncated [body] is only the start of the feed (see [com.app.newspaperss.core.net.HttpClient.getFeed]):
     *   the items read before the cut are the feed. Not for a JSON Feed, which can't be read in part.
     */
    fun parse(body: String, feedUrl: String, truncated: Boolean = false): Feed {
        val text = body.trimStart('﻿', ' ', '\t', '\r', '\n')
        val feed = if (text.startsWith("{")) parseJson(text, feedUrl) else parseXml(text, feedUrl, truncated)
        return feed.copy(items = ownAddresses(feed.items))
    }
    /**
     * Gives each item of a microblog its own address: some link every entry to the one page that
     * holds them all (sive.rs/d), with the entry's own page only in its id (sive.rs/d/1291). That
     * page would be fetched as each entry's article, and the entries taken for one story. Only an
     * id on the same site, and only where items share a link.
     */
    private fun ownAddresses(items: List<FeedItem>): List<FeedItem> {
        val shared = items.groupingBy { it.url }.eachCount().filterValues { it > 1 }.keys
        if (shared.isEmpty()) return items
        return items.map { item ->
            val id = item.guid
            val sameSite = runCatching { host(URI(id)) == host(URI(item.url)) }.getOrDefault(false)
            if (item.url in shared && id != item.url && id.startsWith("http") && sameSite) item.copy(url = id) else item
        }
    }

    private fun host(uri: URI) = uri.host?.lowercase()?.removePrefix("www.")


    /** True if [body] looks like a feed rather than an HTML page. */
    fun looksLikeFeed(body: String): Boolean {
        val head = body.trimStart('﻿', ' ', '\t', '\r', '\n').take(2048)
        if (head.startsWith("{")) return head.contains("jsonfeed.org")
        return Regex("<(rss|feed|rdf:RDF)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(head)
    }

    private fun parseXml(text: String, feedUrl: String, truncated: Boolean): Feed {
        // Entities that expand into other entities are refused before parsing: nested, they can
        // grow to gigabytes ("billion laughs"). Plain ones (an old CMS declaring &nbsp;) are read.
        val root = ROOT.find(text)?.range?.first ?: text.length
        val prolog = text.substring(0, minOf(root, MAX_PROLOG))
        if (ENTITY.findAll(prolog).any { it.groupValues[1].isNotEmpty() || ENTITY_REF.containsMatchIn(it.groupValues[2]) || '%' in it.groupValues[2] }) {
            throw FeedParseException("Not a readable feed: it declares entities made of other entities.")
        }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // Feeds often use HTML entities like &nbsp; that plain XML rejects;
        // kxml's relaxed mode (also the Android parser) tolerates them.
        try {
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: XmlPullParserException) {
        }
        parser.setInput(StringReader(text))
        try {
            return XmlFeedReader(parser, feedUrl, truncated).read()
        } catch (e: XmlPullParserException) {
            throw FeedParseException("Not a readable feed: ${e.message}", e)
        }
    }

    private class XmlFeedReader(private val p: XmlPullParser, private val feedUrl: String, private val truncated: Boolean) {
        private var feedTitle: String? = null
        private var siteUrl: String? = null
        private val items = mutableListOf<FeedItem>()
        private var sawRoot = false

        fun read(): Feed {
            try {
                readAll()
            } catch (e: XmlPullParserException) {
                // The relaxed parser takes the end of the text as the document's, but a cut inside
                // a tag ("<descri") is an error. The items before it are whole.
                if (!truncated || items.isEmpty()) throw e
            }
            if (!sawRoot) throw FeedParseException("Empty document")
            // The relaxed parser closes what the cut left open, so the last item read may be only
            // part of one: it goes. A whole one lost from a feed this long costs nothing.
            return Feed(feedTitle, siteUrl, if (truncated) items.dropLast(1) else items)
        }

        private fun readAll() {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val name = p.name
                when {
                    !sawRoot -> {
                        if (name != "rss" && name != "feed" && name != "RDF") {
                            throw FeedParseException("Not a feed: root element <$name>")
                        }
                        sawRoot = true
                    }
                    name == "item" || name == "entry" -> readItem(name)?.let(items::add)
                    p.namespace !in FEED_NAMESPACES -> {}
                    name == "title" && feedTitle == null && p.depth <= 3 -> feedTitle = cleanTitle(readText())
                    name == "link" && siteUrl == null && p.depth <= 3 -> readLink()?.let { siteUrl = it }
                }
            }
        }

        private fun readItem(tag: String): FeedItem? {
            val depth = p.depth
            var title: String? = null
            var link: String? = null
            var guid: String? = null
            var guidIsLink = false
            var content: String? = null
            var summary: String? = null
            var author: String? = null
            var published: String? = null
            var updated: String? = null
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth && p.name == tag)) {
                if (p.eventType == XmlPullParser.END_DOCUMENT) break
                if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
                val ns = p.namespace
                if (ns !in FEED_NAMESPACES && ns != CONTENT_NS && ns != DC_NS) {
                    skip()
                    continue
                }
                when (p.name) {
                    "title" -> if (ns in FEED_NAMESPACES) title = readText() else skip()
                    "link" -> readLink()?.let { if (link == null) link = it }
                    "guid" -> {
                        guidIsLink = p.getAttributeValue(null, "isPermaLink") != "false"
                        guid = readText()
                    }
                    "id" -> guid = readText()
                    "encoded" -> if (ns == CONTENT_NS) content = readText() else skip()
                    "content" -> if (ns in FEED_NAMESPACES) content = readAtomContent() else skip()
                    "description", "summary" -> if (ns in FEED_NAMESPACES) summary = readAtomContent() else skip()
                    "creator" -> if (ns == DC_NS) author = readText() else skip()
                    "author" -> if (ns in FEED_NAMESPACES) author = readAuthor() else skip()
                    "pubDate", "published", "issued" -> published = readText()
                    "date" -> if (ns == DC_NS) published = readText() else skip()
                    "updated", "modified" -> updated = readText()
                    else -> skip()
                }
            }
            val url = link?.takeIf { it.isNotBlank() }
                ?: guid?.takeIf { guidIsLink && it.startsWith("http") }
                ?: return null
            val absolute = resolve(url)
            return FeedItem(
                guid = guid?.takeIf { it.isNotBlank() } ?: absolute,
                url = absolute,
                title = cleanTitle(title).ifBlank { absolute },
                contentHtml = (content?.takeIf { it.isNotBlank() } ?: summary?.takeIf { it.isNotBlank() }),
                author = author?.trim()?.takeIf { it.isNotEmpty() },
                published = FeedDates.parse(published) ?: FeedDates.parse(updated),
            )
        }

        /** RSS <link>text</link>, or Atom <link rel="alternate" href=...>; other rels give null. */
        private fun readLink(): String? {
            val href = p.getAttributeValue(null, "href")
            if (href != null) {
                val rel = p.getAttributeValue(null, "rel") ?: "alternate"
                skip()
                return if (rel == "alternate") href.trim() else null
            }
            return readText().trim().takeIf { it.isNotEmpty() }
        }

        private fun readAuthor(): String? {
            // RSS <author> is text (often an email); Atom's has a <name> child.
            val depth = p.depth
            var name: String? = null
            val text = StringBuilder()
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(p.text)
                    XmlPullParser.START_TAG -> if (p.name == "name") name = readText() else skip()
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return name ?: text.toString().trim().takeIf { it.isNotEmpty() }
        }

        /** Atom type="xhtml" content is inline markup, not text; serialize it back to HTML. */
        private fun readAtomContent(): String {
            if (p.getAttributeValue(null, "type") != "xhtml") return readText()
            val depth = p.depth
            val out = StringBuilder()
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        // The wrapping <div xmlns=xhtml> is required by the spec, not content.
                        if (p.depth == depth + 1 && p.name == "div" && p.namespace == XHTML_NS) continue
                        out.append('<').append(p.name)
                        for (i in 0 until p.attributeCount) {
                            out.append(' ').append(p.getAttributeName(i)).append("=\"")
                                .append(esc(p.getAttributeValue(i))).append('"')
                        }
                        out.append('>')
                    }
                    XmlPullParser.END_TAG ->
                        if (!(p.depth == depth + 1 && p.name == "div" && p.namespace == XHTML_NS)) {
                            out.append("</").append(p.name).append('>')
                        }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> out.append(esc(p.text))
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return out.toString()
        }

        private fun readText(): String {
            val depth = p.depth
            val out = StringBuilder()
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> out.append(p.text)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return out.toString()
        }

        private fun skip() {
            val depth = p.depth
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                if (p.eventType == XmlPullParser.END_DOCUMENT) break
            }
        }

        private fun resolve(url: String) = resolveUrl(feedUrl, url)
    }

    private fun parseJson(text: String, feedUrl: String): Feed {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw FeedParseException("Not a readable JSON Feed: ${e.message}", e)
        } ?: throw FeedParseException("Not a JSON Feed")
        if (root.str("version")?.contains("jsonfeed.org") != true) throw FeedParseException("Not a JSON Feed")
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o.str("url") ?: o.str("external_url"))?.let { resolveUrl(feedUrl, it) } ?: return@mapNotNull null
            val author = (o["authors"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("name") }
                ?: (o["author"] as? JsonObject)?.str("name")
            FeedItem(
                guid = o.str("id") ?: url,
                url = url,
                title = cleanTitle(o.str("title")).ifBlank { url },
                contentHtml = o.str("content_html") ?: o.str("content_text")?.let(HtmlCleaner::textToHtml) ?: o.str("summary"),
                author = author,
                published = FeedDates.parse(o.str("date_published")) ?: FeedDates.parse(o.str("date_modified")),
            )
        }
        return Feed(root.str("title"), root.str("home_page_url"), items)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }


    /** Titles may be entity-encoded HTML ("Q&amp;A", "<em>New</em>"); reduce to plain text. */
    internal fun cleanTitle(raw: String?): String {
        val t = raw?.trim() ?: return ""
        val plain = if (t.contains('<') || t.contains('&')) Jsoup.parse(t).text() else t
        return plain.replace(Regex("\\s+"), " ").trim()
    }

}

/**
 * [url] made absolute against [base]. A link java.net.URI refuses (a `|` or a brace in its query)
 * goes through the stricter cleaner, which percent-encodes it; one even that can't read is kept as
 * it came.
 */
internal fun resolveUrl(base: String, url: String): String = try {
    val baseUri = URI(base)
    // Against "https://host" (no path) Android's URI glues the reference onto the host name.
    val from = if (baseUri.rawPath.isNullOrEmpty() && !baseUri.isOpaque) baseUri.resolve("/") else baseUri
    from.resolve(url.trim().replace(" ", "%20")).toString()
} catch (_: Exception) {
    HtmlCleaner.absoluteUrl(url, base, HtmlCleaner.LINK_SCHEMES) ?: url.trim()
}
