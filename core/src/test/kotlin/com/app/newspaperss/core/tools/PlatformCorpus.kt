package com.app.newspaperss.core.tools

import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Not a test: a developer tool that adds real posts to the platform corpus in
 * core/src/test/resources/platforms, which [com.app.newspaperss.core.extract.PlatformCorpusTest]
 * runs the extractor over. For each feed it saves its newest few items: the feed's content, the
 * same content as tt-rss's API passes it on (run through a tt-rss checkout's own Sanitizer by
 * tools/ttrss-sanitize.php), and the article's page, plus the story's page for a link post (see
 * [LinkPosts]).
 *
 *   ./gradlew :core:platformCorpus --args="/path/to/tt-rss substack https://example.substack.com/feed 3"
 *
 * The articles' words are scrambled before they're saved, so the corpus keeps each platform's
 * markup without republishing anyone's writing: each word maps to the same made-up word every
 * time (so a caption repeating an image's hover text still repeats it), and numbers, punctuation,
 * common English words and the words page cleanup looks for are kept. Scripts and styles are
 * dropped from pages, except JSON-LD, which page extraction reads.
 */
fun main(args: Array<String>) = runBlocking {
    val (ttrss, platform, feedUrl) = args
    val count = args.getOrNull(3)?.toInt() ?: 3
    val http = OkHttpHttpClient()
    val response = http.get(feedUrl)
    check(response.isSuccessful) { "feed answered ${response.code}" }
    val feed = FeedParser.parse(response.body, response.finalUrl)
    val siteUrl = feed.siteUrl ?: response.finalUrl
    val root = File("src/test/resources/platforms")
    for (item in feed.items.take(count)) {
        val slug = item.url.substringAfter("//").substringAfter('/').trimEnd('/').substringAfterLast('/')
            .substringBefore('?').substringBefore('.').ifBlank { item.guid.hashCode().toUInt().toString() }.take(40)
        val dir = File(root, "$platform/${feedUrl.substringAfter("//").substringBefore('/')}-$slug").apply { mkdirs() }
        val content = item.contentHtml.orEmpty()
        val sanitized = ProcessBuilder("php", "../tools/ttrss-sanitize.php", ttrss, siteUrl).start().run {
            outputStream.use { it.write(content.toByteArray()) }
            inputStream.readBytes().toString(Charsets.UTF_8).also { check(waitFor() == 0) { "php failed" } }
        }
        val page = runCatching { http.get(item.url) }.getOrNull()
        // A link post's story is what the app fetches (FeedSync makes it the article's address).
        val storyUrl = LinkPosts.storyUrl(item.url, content, siteUrl)
        val story = storyUrl?.let { runCatching { http.get(it) }.getOrNull() }
        val meta = buildJsonObject {
            put("url", item.url)
            put("title", Scrambler.text(item.title))
            item.author?.let { put("author", it) }
            put("feedUrl", feedUrl)
            put("siteUrl", siteUrl)
            put("pageCode", page?.code ?: 0)
            page?.let { put("pageUrl", it.finalUrl) }
            storyUrl?.let { put("storyUrl", it) }
            story?.let { put("storyCode", it.code); put("storyPageUrl", it.finalUrl) }
        }
        File(dir, "item.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), meta) + "\n")
        gzip(File(dir, "feed.html.gz"), Scrambler.fragment(content))
        gzip(File(dir, "ttrss.html.gz"), Scrambler.fragment(sanitized))
        if (page != null && page.isSuccessful) gzip(File(dir, "page.html.gz"), Scrambler.page(page.body))
        if (story != null && story.isSuccessful) gzip(File(dir, "story.html.gz"), Scrambler.page(story.body))
        println("${dir.name}: feed ${content.length}, page ${page?.code} ${page?.body?.length}")
    }
}

private fun gzip(file: File, text: String) = GZIPOutputStream(file.outputStream()).use { it.write(text.toByteArray()) }

internal object Scrambler {
    fun fragment(html: String): String = Jsoup.parseBodyFragment(html).body().also { scrambleTree(it) }.html()

    fun page(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script:not([type=application/ld+json]), style, link[rel=stylesheet], link[rel=preload], svg, template").remove()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { Json.parseToJsonElement(script.data()) }.getOrNull()
            if (json == null) script.remove() else script.text("").appendChild(org.jsoup.nodes.DataNode(scrambleJson(json).toString()))
        }
        // Titles too, so a page's title still matches its feed item's (scrambled the same way).
        doc.select("meta[name=description], meta[property=og:description], meta[name=twitter:description], meta[property=og:title], meta[name=twitter:title]").forEach {
            it.attr("content", text(it.attr("content")))
        }
        doc.title(text(doc.title()))
        scrambleTree(doc.body())
        return doc.outerHtml()
    }

    private fun scrambleTree(root: Element) {
        root.allElements.forEach { el ->
            el.childNodes().toList().forEach { node ->
                when {
                    node is Comment -> node.remove()
                    node is TextNode && el.tagName() !in setOf("script", "style") -> node.text(text(node.wholeText))
                }
            }
            // Text the page shows from attributes: hover text, alt text, a Substack Note's body.
            listOf("title", "alt", "aria-label").forEach { if (el.hasAttr(it)) el.attr(it, text(el.attr(it))) }
            if (el.hasAttr("data-attrs")) {
                runCatching { Json.parseToJsonElement(el.attr("data-attrs")) }.getOrNull()?.let { el.attr("data-attrs", scrambleJson(it).toString()) }
            }
        }
    }

    private val PROSE_KEYS = setOf("body", "articleBody", "description", "headline", "name", "caption", "text")

    private fun scrambleJson(json: JsonElement, key: String? = null): JsonElement = when (json) {
        is JsonObject -> JsonObject(json.mapValues { (k, v) -> scrambleJson(v, k) })
        is JsonArray -> JsonArray(json.map { scrambleJson(it, key) })
        is JsonPrimitive -> if (json.isString && key in PROSE_KEYS && !json.content.startsWith("http")) JsonPrimitive(text(json.content)) else json
    }

    private val WORD = Regex("\\p{L}+")

    fun text(s: String): String = WORD.replace(s) { m -> word(m.value) }

    private fun word(w: String): String {
        val lower = w.lowercase()
        if (lower in KEEP || w.length == 1) return w
        // A made-up word of the same length, from consonant-vowel pairs, the same for each word.
        var h = lower.hashCode().toLong() and 0xffffffffL
        val made = buildString {
            while (length < w.length) {
                append(CONSONANTS[(h % CONSONANTS.length).toInt()]); h = h / CONSONANTS.length + 7919 * length
                if (length < w.length) { append(VOWELS[(h % VOWELS.length).toInt()]); h = h / VOWELS.length + 104729 * length }
            }
        }
        return made.mapIndexed { i, c -> if (w[i].isUpperCase()) c.uppercaseChar() else c }.joinToString("")
    }

    private const val CONSONANTS = "bcdfghjklmnprstvz"
    private const val VOWELS = "aeiou"

    // Common English words (so the text still reads as English to the language check) and the words
    // page cleanup and the paywall checks look for.
    private val KEEP = (
        "the of and to in is it that for on with as was at by be this have from or an are not but had his they you " +
            "she he we her their there been has were which one all would will can if more when who so what about out up " +
            "its them than then into some could time these two may other like only also new just over after first any " +
            "our my your me us most many such do does did no yes how where why our very even back because well way " +
            "share subscribe subscribed subscription subscribers subscriber sign read reading listen article articles essay " +
            "story stories episode min minute minutes advertisement related recommended click here follow newsletter promotion " +
            "skip past next might popular coverage posts post content upgrade paid free continue member members premium " +
            "comment comments leave like likes restack footnote footnotes notes note previous view browser unsubscribe " +
            "podcast transcript video audio photo image caption credit getty editor update updated published " +
            "receive support work consider becoming become reader supported publication thanks thank gift forward friend " +
            "reply web website weekly week daily email inbox expect offers already account log in trial days access " +
            "archive archives full keep get paying sponsor sponsors sponsored advertise advertising watch youtube add story picks"
        ).split(' ').toSet()
}
