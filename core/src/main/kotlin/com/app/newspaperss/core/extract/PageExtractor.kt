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
    /** A webcomic's own comic, each panel in a `<figure>`, where its page marks it (ComicControl's `#cc-comic`, xkcd's `#comic`). */
    val comicImage: String? = null,
    /** The language the page declares, as written (`<html lang>`, else `og:locale`). */
    val language: String? = null,
    /** The site's own name (`og:site_name`, else the JSON-LD publisher). */
    val siteName: String? = null,
    /** The page says the post is for paying subscribers, so what's on it is only the free part. */
    val paywalled: Boolean = false,
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
    private const val MAX_COMIC_PANELS = 20
    // Where webcomic engines put the comic: ComicControl (Hiveworks sites), xkcd and similar.
    private const val COMIC_IMAGE = "img#cc-comic, #cc-comicbody img, #comic img, img#comic, #comic-image img"

    // The paywalls whose pitch can outweigh the free part, so Readability would take it for the
    // article. Not Substack's or Ghost's: Readability already passes those over, and without them
    // there it reaches further, for the post's header.
    private const val PAYWALL_PITCH = ".mepr-unauthorized-message, .passport-marketing-page"
    // What platforms put in place of the rest of a paid post: Ghost's upgrade box (from its
    // {{content}} helper, so every theme has it), Substack's paywall, and the sales pitches of
    // MemberPress (a WordPress membership plugin) and Passport (Stratechery's).
    private const val PAYWALL = ".gh-post-upgrade-cta, [data-testid=paywall], $PAYWALL_PITCH"
    // A sign-in form in the article itself, where a site with its own paywall (a magazine's) puts it
    // after the free part. Only in a lone <article>: a site's header or a modal can hold one anywhere.
    private const val SIGN_IN_IN_ARTICLE = "input[type=password]"
    private const val SIGN_IN_BOX_MAX_WORDS = 300

    private val NOT_MAIN_IMAGE = setOf("logo", "avatar", "headshot", "author", "profile", "icon", "icons")
    private const val JSON_LD_PREFERENCE_RATIO = 1.5
    private const val FREE_PART_PROBE = 60

    fun extract(html: String, url: String): PageContent {
        val doc = Jsoup.parse(html, url)
        val jsonLd = jsonLdObjects(doc)
        val siteName = doc.metaContent("og:site_name") ?: jsonLd.firstNotNullOfOrNull { (it["publisher"] as? JsonObject)?.string("name") }
        // Once JSON-LD is read, nothing needs these, and Readability clones the whole document:
        // on script-heavy sites inline scripts are most of the page, parsed and copied for nothing.
        // Declarative shadow DOM is shown on the page, so its templates stay.
        doc.select("script, style, svg, template:not([shadowrootmode])").remove()
        removeOverlays(doc)
        // Not schema.org's isAccessibleForFree: metered sites set it false and serve the whole story.
        // After the overlays go, so a sign-in dialog inside the article doesn't count.
        val paywalled = doc.selectFirst(PAYWALL) != null || signInPaywall(doc)
        HtmlCleaner.removeScreenReaderOnly(doc.body())
        // Readability drops every <footer> and <aside>, footnotes and quotes' credits too.
        HtmlCleaner.keepFootnoteContainers(doc.body())
        HtmlCleaner.keepQuoteCredits(doc.body())
        // Readability drops classes and styles, and with them the small capitals.
        HtmlCleaner.capitalizeSmallCaps(doc.body())
        // After the passes above, as Readability's pick it's compared with comes after them too.
        val pitches = doc.select(PAYWALL_PITCH)
        // What comes before the pitch in its box, the free part, if the box holds it at all.
        val freePart = pitches.firstOrNull()?.let { pitch ->
            Element("div").apply { pitch.parent()?.children()?.takeWhile { it !== pitch }?.forEach { appendChild(it.clone()) } }
        }
        pitches.remove()

        val readability = runCatching { Readability4JExtended(url, doc.clone()).parse() }.getOrNull()
        val author = listOfNotNull(
            doc.metaContent("author")?.takeUnless { it.startsWith("http") },
            jsonLd.firstNotNullOfOrNull { authorName(it["author"]) },
            readability?.byline?.replace(BY_PREFIX, ""),
        ).firstNotNullOfOrNull { cleanAuthor(it) }
        val title = title(doc, jsonLd, siteName.orEmpty(), url, author.orEmpty())

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
        // With next to nothing free, Readability can pass over the free part for a sidebar.
        val freeText = freePart?.text()?.trim().orEmpty()
        val withFreePart = if (freeText.isNotEmpty() && freeText.takeLast(FREE_PART_PROBE) !in Jsoup.parseBodyFragment(chosen.html).text()) {
            candidate("paywall", freePart!!.html()) ?: chosen
        } else chosen
        // <main>, or a lone <article>: several <article>s are usually related-story cards, whose
        // thumbnails aren't this page's.
        val main = doc.selectFirst("main") ?: doc.select("article").singleOrNull()
        return withFreePart.copy(
            mainImage = main?.let(::mainImage),
            articleText = main?.text()?.takeIf { it.isNotBlank() },
            description = doc.metaContent("og:description"),
            comicImage = doc.select(COMIC_IMAGE).take(MAX_COMIC_PANELS).joinToString("") { figureOf(it) }.ifEmpty { null },
            language = doc.selectFirst("html")?.let { html -> html.attr("lang").ifBlank { html.attr("xml:lang") } }?.ifBlank { null }
                ?: doc.metaContent("og:locale"),
            siteName = siteName?.trim()?.ifBlank { null },
            paywalled = paywalled,
        )
    }

    /**
     * The first sizeable image in the page's `<article>` or `<main>`, as a `<figure>` with just the
     * attributes [HtmlCleaner] reads. Not `og:image`: many sites use one share card on every page.
     * Logos, avatars, SVGs and images declared small don't count.
     */
    private fun mainImage(main: Element): String? {
        val img = main.select("img").firstOrNull { img ->
            // Whole words of the class and address only: a cartoon's alt text can say "an iconic
            // moment", and "silicon-valley.jpg" isn't an icon.
            val marks = "${img.className()} ${img.attr("src")}".lowercase().split(NON_ALNUM).toSet()
            val sources = listOf("src", "srcset", "data-src", "data-srcset").map { img.attr(it) }.filter { it.isNotBlank() && !it.startsWith("data:") }
            val small = listOf("width", "height").any { dim -> img.attr(dim).toIntOrNull()?.let { it < MAIN_IMAGE_MIN_PX } == true }
            sources.isNotEmpty() && !small && NOT_MAIN_IMAGE.none { it in marks } &&
                sources.none { it.substringBefore('?').lowercase().endsWith(".svg") }
        } ?: return null
        return figureOf(img)
    }

    /** [img] alone in a `<figure>`, with just the attributes [HtmlCleaner] reads. */
    private fun figureOf(img: Element): String {
        val figure = Element("figure")
        val copy = figure.appendElement("img")
        for (name in listOf("src", "srcset", "data-src", "data-srcset", "alt", "title")) img.attr(name).takeIf { it.isNotBlank() }?.let { copy.attr(name, it) }
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

    /**
     * A sign-in form in a lone `<article>` with an offer to subscribe or buy around it: a
     * magazine's own paywall. A form to log in and comment has no such offer.
     */
    private fun signInPaywall(doc: Document): Boolean {
        val article = doc.select("article").singleOrNull() ?: return false
        val field = article.selectFirst(SIGN_IN_IN_ARTICLE) ?: return false
        return field.parents().takeWhile { it !== article }
            .takeWhile { HtmlCleaner.countWords(it) <= SIGN_IN_BOX_MAX_WORDS }
            .any { PAYWALL_OFFER.containsMatchIn(it.text()) }
    }

    /**
     * The page's title: its og:title, JSON-LD headline, twitter:title or `<title>`, without a
     * trailing site or author name. Where the og:title wraps the page's own headline in more
     * ("Donald Sassoon, Changing the Guard, NLR 160"), the headline alone.
     */
    fun title(doc: Document, jsonLd: List<JsonObject> = jsonLdObjects(doc), siteName: String = "", url: String = "", author: String = ""): String? {
        val jsonLdHeadline = jsonLd.firstNotNullOfOrNull { it.string("headline") }
        val raw = listOfNotNull(doc.metaContent("og:title"), jsonLdHeadline, doc.metaContent("twitter:title"), doc.title())
            .firstOrNull { it.isNotBlank() } ?: return null
        // Microdata only to shorten a title: a page's first itemprop=headline can be a related story's.
        val headline = (jsonLdHeadline ?: microdataHeadline(doc))?.replace(WHITESPACE, " ")?.trim()
        // Who wrote it, as the page names them anywhere: the og:title may name them where nothing else does.
        val names = listOf(siteName, author) + doc.select("meta[itemprop=author], meta[name=author]").map { it.attr("content") } + url
        if (headline != null && wrapsHeadline(raw.replace(WHITESPACE, " ").trim(), headline, names)) return headline
        return cleanTitle(raw, siteName, url, author)
    }

    // The page's own heading only: a related story's card can carry itemprop=headline too.
    private fun microdataHeadline(doc: Document): String? = doc.selectFirst("h1[itemprop=headline]")?.text()

    /**
     * Whether [title] is [headline] with more around it, set off by punctuation, and the more names
     * the author, the site or the address ("Donald Sassoon, Changing the Guard, NLR 160"): not a
     * subtitle ("The Long Goodbye: Britain after Brexit"), and not a headline cut short (JSON-LD's
     * is often limited to 110 characters), which runs on without punctuation.
     */
    private fun wrapsHeadline(title: String, headline: String, names: List<String>): Boolean {
        val at = title.indexOf(headline)
        if (at < 0 || title.length == headline.length || headline.split(' ').size < 2) return false
        val before = title.substring(0, at)
        val after = title.substring(at + headline.length)
        if (!((before.isEmpty() || WRAPPER_BEFORE.matches(before)) && (after.isEmpty() || WRAPPER_AFTER.matches(after)))) return false
        // The extra must be mostly names, numbers and dates: "Why It's Time to Act Now" (on
        // time.com) has the site's name in it, but it's a subtitle.
        val extra = words(before + after)
        val hostWords = runCatching { URI(names.last()).host }.getOrNull().orEmpty().lowercase().split('.', '-')
            .filter { it.length > 2 && it !in HOST_NOISE && it != "the" }
        val nameWords = names.dropLast(1).flatMap(::words).filter { it.length > 1 }.toSet() + hostWords
        val named = extra.count { it in nameWords }
        val other = extra.count { it !in nameWords && !it.all(Char::isDigit) && it !in MONTHS }
        return named > 0 && other <= WRAPPER_OTHER_WORDS
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

    private fun words(s: String) = s.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }

    private fun Document.metaContent(name: String): String? =
        selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    internal fun jsonLdObjects(doc: Document): List<JsonObject> {
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
    private const val WRAPPER_OTHER_WORDS = 2
    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "issue", "vol", "no",
    )
    private val PAYWALL_OFFER = Regex("\\bsubscri|\\bbuy\\b|\\bpurchase\\b", RegexOption.IGNORE_CASE)
    private val TITLE_SEPARATORS = Regex("\\s+[|\\-–—:·•]\\s+")
    private val WRAPPER_BEFORE = Regex(".*\\S\\s*[,|\\-–—:·•]\\s+")
    private val WRAPPER_AFTER = Regex("\\s*[,|\\-–—:·•]\\s+\\S.*")
    private val HOST_NOISE = setOf("www", "com", "org", "net")
    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val BY_PREFIX = Regex("^\\s*by\\s+", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_TAG = Regex("<\\s*p[\\s>]", RegexOption.IGNORE_CASE)
}
