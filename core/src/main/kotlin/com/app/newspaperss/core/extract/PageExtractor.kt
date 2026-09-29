package com.app.newspaperss.core.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI

/** The article found in a web page, not yet cleaned for e-ink (see [HtmlCleaner]). */
internal data class PageContent(
    val html: String,
    val title: String?,
    val author: String?,
    val extractor: String,
    val wordCount: Int,
    /** The page's main image as a `<figure>`, for posts that are an image (a cartoon, a comic). */
    val mainImage: String? = null,
    /** The text of the page's `<article>` or `<main>`, to tell article text from footer text. */
    val articleText: String? = null,
    /** `og:description`, a stand-in caption for an image post. */
    val description: String? = null,
)

/**
 * Pulls the article out of a page. Stages, keeping the first with at least
 * [MIN_WORDS] words, else the longest:
 * 1. Readability4J, the algorithm behind Firefox's Reader View.
 * 2. schema.org JSON-LD `articleBody`, which many sites (paywalled ones
 *    too) fill with the full text. It also wins over a long-enough
 *    Readability result when it has [JSON_LD_PREFERENCE_RATIO] times the
 *    words, because then Readability only saw a paywall preview.
 * 3. The whole body; the cleaner strips it down.
 */
internal object PageExtractor {
    const val MIN_WORDS = 150
    private const val MAIN_IMAGE_MIN_PX = 200
    private val NOT_MAIN_IMAGE = listOf("logo", "avatar", "headshot", "author", "profile", "icon")
    private const val JSON_LD_PREFERENCE_RATIO = 1.5

    fun extract(html: String, url: String): PageContent {
        val doc = Jsoup.parse(html, url)
        val jsonLd = jsonLdObjects(doc)
        val siteName = doc.metaContent("og:site_name") ?: jsonLd.firstNotNullOfOrNull { (it["publisher"] as? JsonObject)?.string("name") }
        removeOverlays(doc)
        HtmlCleaner.removeScreenReaderOnly(doc.body())

        val readability = runCatching { Readability4JExtended(url, doc.clone()).parse() }.getOrNull()
        val author = listOfNotNull(
            doc.metaContent("author")?.takeUnless { it.startsWith("http") },
            jsonLd.firstNotNullOfOrNull { authorName(it["author"]) },
            readability?.byline?.replace(BY_PREFIX, ""),
        ).firstNotNullOfOrNull { cleanAuthor(it) }
        val rawTitle = listOfNotNull(
            doc.metaContent("og:title"),
            jsonLd.firstNotNullOfOrNull { it.string("headline") },
            doc.metaContent("twitter:title"),
            doc.title(),
        ).firstOrNull { it.isNotBlank() }
        val title = rawTitle?.let { cleanTitle(it, siteName.orEmpty(), url, author.orEmpty()) }

        fun candidate(name: String, content: String?) = content?.takeIf { it.isNotBlank() }
            ?.let { PageContent(it, title, author, name, HtmlCleaner.countWords(it)) }

        // Readability wraps its result in <div id="readability-page-1">, an id every article would share.
        val fromReadability = candidate("readability", readability?.articleContent?.let { content ->
            content.select("[id^=readability-]").removeAttr("id")
            content.html()
        })
        val fromJsonLd = candidate("json-ld", jsonLdBody(jsonLd))
        val chosen = when {
            fromReadability != null && fromReadability.wordCount >= MIN_WORDS -> {
                val jsonLdHasMuchMore = fromJsonLd != null && fromJsonLd.wordCount >= JSON_LD_PREFERENCE_RATIO * fromReadability.wordCount
                if (jsonLdHasMuchMore) fromJsonLd!! else fromReadability
            }
            fromJsonLd != null && fromJsonLd.wordCount >= MIN_WORDS -> fromJsonLd
            else -> listOfNotNull(fromReadability, fromJsonLd).maxByOrNull { it.wordCount }
                ?: PageContent(doc.body().html(), title, author, "body", HtmlCleaner.countWords(doc.body()))
        }
        val main = doc.select("article, main")
        return chosen.copy(
            mainImage = mainImage(main),
            articleText = main.text().takeIf { it.isNotBlank() },
            description = doc.metaContent("og:description"),
        )
    }

    /**
     * The first sizeable image in the page's `<article>` or `<main>`, as a `<figure>` with just the
     * attributes [HtmlCleaner] reads. Not `og:image`: many sites use one share card on every page.
     * Logos, avatars, SVGs and images declared small don't count.
     */
    private fun mainImage(main: Elements): String? {
        val img = main.select("img").firstOrNull { img ->
            // Class and address only: a cartoon's alt text can say "an iconic moment".
            val marks = "${img.className()} ${img.attr("src")}".lowercase()
            val sources = listOf("src", "srcset", "data-src", "data-srcset").map { img.attr(it) }.filter { it.isNotBlank() && !it.startsWith("data:") }
            val small = listOf("width", "height").any { dim -> img.attr(dim).toIntOrNull()?.let { it < MAIN_IMAGE_MIN_PX } == true }
            sources.isNotEmpty() && !small && NOT_MAIN_IMAGE.none { it in marks } &&
                sources.none { it.substringBefore('?').lowercase().endsWith(".svg") }
        } ?: return null
        val figure = Element("figure")
        val copy = figure.appendElement("img")
        for (name in listOf("src", "srcset", "data-src", "data-srcset", "alt")) img.attr(name).takeIf { it.isNotBlank() }?.let { copy.attr(name, it) }
        return figure.outerHtml()
    }

    /**
     * Removes cookie/consent banners and modal dialogs. They have to go
     * before extraction: Readability drops attributes, so their text would
     * otherwise reach the cleaner looking like ordinary paragraphs.
     */
    fun removeOverlays(doc: Document) {
        val body = doc.body()
        val total = HtmlCleaner.countWords(body).coerceAtLeast(1)
        for (el in body.select("*")) {
            if (el === body || el.ownerDocument() == null) continue
            val marks = "${el.className()} ${el.id()}".lowercase()
            val isOverlay = el.tagName() == "dialog" || el.attr("role") in DIALOG_ROLES ||
                el.attr("aria-modal") == "true" || OVERLAY_MARKERS.any { it in marks }
            if (isOverlay && HtmlCleaner.isSmallPart(el, total)) el.remove()
        }
    }

    /** Removes a trailing site or author name: "E-reader - Wikipedia" becomes "E-reader". */
    fun cleanTitle(title: String, siteName: String = "", url: String = "", author: String = ""): String {
        val t = title.replace(WHITESPACE, " ").trim()
        val separators = TITLE_SEPARATORS.findAll(t).toList()
        if (separators.isEmpty()) return t
        val last = separators.last()
        val suffixText = t.substring(last.range.last + 1)
        val suffix = key(suffixText)
        if (suffix.isEmpty() || suffixText.split(WHITESPACE).size > 5) return t
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()
        val hostWords = host.split('.', '-').filter { it.length > 2 && it !in HOST_NOISE }
        val site = key(siteName)
        val authorKey = key(author)
        val matches = (site.isNotEmpty() && (site in suffix || suffix in site)) ||
            hostWords.any { it in suffix } ||
            (authorKey.length > 3 && (authorKey in suffix || suffix in authorKey))
        return if (matches) t.substring(0, last.range.first).trim() else t
    }

    /** Metadata extractors sometimes grab a whole block of page text as the "author". */
    fun cleanAuthor(author: String?): String? {
        val a = author?.replace(WHITESPACE, " ")?.trim() ?: return null
        return a.takeIf { it.isNotEmpty() && it.length <= 80 && it.split(" ").size <= 10 }
    }

    private fun key(s: String) = s.lowercase().replace(NON_ALNUM, "")

    private fun Document.metaContent(name: String): String? =
        selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private fun jsonLdObjects(doc: Document): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { Json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            val queue = ArrayDeque<JsonElement>().apply { add(root) }
            while (queue.isNotEmpty()) {
                when (val item = queue.removeFirst()) {
                    is JsonArray -> queue.addAll(item)
                    is JsonObject -> {
                        found += item
                        (item["@graph"] as? JsonArray)?.let { queue.addAll(it) }
                    }
                    else -> {}
                }
            }
        }
        return found
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun authorName(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.string("name")
        is JsonArray -> element.mapNotNull { authorName(it) }.joinToString(", ").ifEmpty { null }
        else -> null
    }

    private fun jsonLdBody(objects: List<JsonObject>): String? {
        val body = objects.mapNotNull { it.string("articleBody") }.maxByOrNull { it.length } ?: return null
        return if (PARAGRAPH_TAG.containsMatchIn(body)) body else HtmlCleaner.textToHtml(body)
    }

    // class/id substrings of cookie and consent banners, including common consent-management plugins.
    private val OVERLAY_MARKERS = listOf("cookie", "consent", "gdpr", "cmplz", "onetrust", "didomi", "usercentrics", "truste")
    private val DIALOG_ROLES = setOf("dialog", "alertdialog")
    private val TITLE_SEPARATORS = Regex("\\s+[|\\-–—:·•]\\s+")
    private val HOST_NOISE = setOf("www", "com", "org", "net")
    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val BY_PREFIX = Regex("^\\s*by\\s+", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_TAG = Regex("<\\s*p[\\s>]", RegexOption.IGNORE_CASE)
}
