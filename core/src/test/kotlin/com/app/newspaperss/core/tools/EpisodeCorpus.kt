package com.app.newspaperss.core.tools

import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Not a test: a developer tool that adds a feed to the episode corpus in
 * core/src/test/resources/episodes, which [com.app.newspaperss.core.episodes.EpisodeCorpusTest]
 * runs the episode detector over (see docs/research/podcast-episodes.md).
 *
 * It keeps a snapshot of the feed's newest items, enclosures and podcast tags intact, with their
 * words scrambled as the platform corpus's are ([Scrambler]), so the corpus keeps what detection
 * reads without republishing anyone's writing. Labels are written by hand afterwards, in
 * labels.json beside the snapshot: one per item, saying what a reader would call it.
 *
 *   ./gradlew :core:episodeCorpus --args="name https://example.com/feed 12"
 *   ./gradlew :core:episodeCorpus --args="name /path/to/saved-feed.xml 12 https://example.com/feed"
 *   ./gradlew :core:episodeCorpus --args="--batch /path/to/list"     # one set of arguments a line
 *
 * A saved file is for feeds that refuse the app's client; the last argument records where it came
 * from. It runs in core/, so give a saved file's path in full.
 */
fun main(args: Array<String>) = runBlocking {
    // A batch: one "name source count url" line per feed.
    if (args[0] == "--batch") {
        File(args[1]).readLines().filter { it.isNotBlank() }.forEach { collect(it.trim().split(Regex("\\s+"))) }
    } else {
        collect(args.toList())
    }
}

private suspend fun collect(args: List<String>) {
    val name = args[0]
    val source = args[1]
    val count = args.getOrNull(2)?.toInt() ?: 12
    val xml = if (source.startsWith("http")) {
        OkHttpHttpClient().getFeed(source).also { check(it.isSuccessful) { "feed answered ${it.code}" } }.body
    } else {
        File(source).readText()
    }
    val feedUrl = args.getOrNull(3) ?: source
    val dir = File("src/test/resources/episodes/$name").apply { mkdirs() }
    val snapshot = EpisodeSnapshot.scramble(xml, count)
    GZIPOutputStream(File(dir, "feed.xml.gz").outputStream()).use { it.write(snapshot.toByteArray()) }
    File(dir, "source.txt").writeText("$feedUrl\n")
    println("$name: ${Jsoup.parse(snapshot, "", Parser.xmlParser()).select("item, entry").size} items")
}

/**
 * A feed cut to its newest items, keeping only what detection reads, with every word people wrote
 * scrambled. An allow-list, because feeds carry addresses and names in more places than anyone
 * could list (webMaster, googleplay:email, copyright, bios inside a post's embeds).
 */
internal object EpisodeSnapshot {
    /** Elements holding HTML, as escaped text or CDATA. */
    private val HTML = setOf("description", "content:encoded", "content", "summary", "itunes:summary")

    private val ITEM = HTML + setOf("title", "link", "guid", "id", "enclosure", "media:content", "itunes:duration", "podcast:transcript")
    private val CHANNEL = setOf("title", "itunes:category", "itunes:type", "item", "entry")

    /** What detection reads of a post's HTML: links, players, and Substack's player for uploaded audio. */
    private val ATTRIBUTES = setOf("href", "src", "data-component-name")

    /** What detection reads of the feed's own tags: addresses and types, never a title or a credit. */
    private val TAG_ATTRIBUTES = setOf("url", "href", "type", "length", "rel", "ispermalink", "text")
    private val EMAIL = Regex("[\\w.+-]+(@|%40)[\\w-]+\\.[\\w.-]+")

    fun scramble(xml: String, count: Int): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        doc.outputSettings().prettyPrint(false)
        val items = doc.select("item").ifEmpty { doc.select("entry") }
        items.drop(count).forEach { it.remove() }
        val channel = doc.selectFirst("channel") ?: doc.selectFirst("feed") ?: error("not a feed")
        channel.children().filter { it.tagName().lowercase() !in CHANNEL }.forEach { it.remove() }
        // RSS 1.0 keeps its items (and its image and text input) beside the channel, not in it.
        channel.parent()?.children()?.filter { it !== channel && it.tagName().lowercase() !in CHANNEL }?.forEach { it.remove() }
        doc.select("item, entry").forEach { item ->
            item.children().filter { it.tagName().lowercase() !in ITEM }.forEach { it.remove() }
            // media:content nests its own description and credits; a link carries a title attribute.
            item.children().filter { it.tagName().lowercase() !in HTML && it.tagName() != "title" }.forEach { el ->
                el.children().remove()
                el.attributes().toList().filter { it.key.lowercase() !in TAG_ATTRIBUTES }.forEach { el.removeAttr(it.key) }
            }
        }
        doc.select("channel > itunes|category").forEach { it.children().remove() }
        doc.select("title").forEach { it.text(Scrambler.text(it.wholeText())) }
        doc.select(HTML.joinToString(", ") { it.replace(":", "|") }).forEach { el ->
            val html = clean(Scrambler.fragment(el.wholeText()))
            el.empty()
            el.appendChild(CDataNode(html))
        }
        doc.select("podcast|transcript").forEach { el -> el.attributes().toList().filter { it.key != "url" && it.key != "type" }.forEach { el.removeAttr(it.key) } }
        return EMAIL.replace((doc as Document).outerHtml(), "someone@example.com")
    }

    private fun clean(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        // Nothing detection reads, and scripts carry data the scramble doesn't reach.
        body.select("script, style, noscript, template, svg, button, img, picture").remove()
        body.allElements.forEach { el ->
            val audio = el.attr("data-component-name") == "AudioPlaceholder"
            el.attributes().toList().forEach { a ->
                when {
                    a.key == "data-attrs" && audio -> el.attr("data-attrs", Regex("\"mediaUploadId\":\"[^\"]+\"").find(a.value)?.value?.let { "{$it}" } ?: "{}")
                    a.key !in ATTRIBUTES -> el.removeAttr(a.key)
                    a.value.startsWith("mailto:", ignoreCase = true) -> el.attr(a.key, "mailto:someone@example.com")
                    // A link's query is where gift tokens, referral codes and account ids ride.
                    // Detection reads a link's address only; a player keeps its query (?e=episode).
                    a.key == "href" -> el.attr("href", a.value.substringBefore('?').substringBefore('#'))
                }
            }
        }
        return body.html()
    }
}
