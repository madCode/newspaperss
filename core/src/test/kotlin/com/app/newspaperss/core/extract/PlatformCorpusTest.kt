package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Real posts from the big blog and newsletter platforms (collected by
 * [com.app.newspaperss.core.tools.PlatformCorpus], their words scrambled), each run through the
 * extractor twice: with the feed's content as the platform sends it, and as tt-rss passes it on.
 * Every result must be a well-formed chapter whose in-book links all land, with no page furniture,
 * leftover embeds or empty blocks. A platform-specific case below pins down each thing found
 * broken in the corpus, so it stays fixed.
 *
 * Each result is also written to core/build/platform-corpus, to read when adding posts.
 */
@RunWith(Parameterized::class)
class PlatformCorpusTest(private val post: Post, @Suppress("unused") private val name: String) {

    class Post(val dir: File, val platform: String, val via: String) {
        private val meta = Json.parseToJsonElement(File(dir, "item.json").readText()).jsonObject
        private fun field(key: String) = meta[key]?.jsonPrimitive?.content
        val url = field("url")!!
        val title = field("title")!!
        val author = field("author")
        val pageUrl = field("pageUrl") ?: url
        val storyUrl = field("storyUrl")
        private val storyPageUrl = field("storyPageUrl") ?: storyUrl
        val content: String? = read(if (via == "tt-rss") "ttrss.html.gz" else "feed.html.gz")
        val page: String? = read("page.html.gz")
        private val story: String? = read("story.html.gz")

        private fun read(name: String) = File(dir, name).takeIf { it.exists() }?.let { f ->
            GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        suspend fun extract(): ExtractedArticle {
            val http = object : HttpClient {
                override suspend fun get(url: String): HttpResponse = when {
                    page != null && (url == this@Post.url || url == pageUrl) -> HttpResponse(200, pageUrl, "text/html; charset=utf-8", page)
                    story != null && (url == storyUrl || url == storyPageUrl) -> HttpResponse(200, storyPageUrl!!, "text/html; charset=utf-8", story)
                    else -> throw IOException("not in the corpus: $url")
                }
                override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
                override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("not used")
            }
            // As FeedSync stores a link post: the story is the article, the item its pitch.
            val input = if (storyUrl != null) ExtractInput(storyUrl, title, content, author, feedUrl = url) else ExtractInput(url, title, content, author)
            return ArticleExtractor(http).extract(input)
        }
    }

    private val article by lazy { runBlocking { post.extract() } }
    private val doc: Document by lazy { Jsoup.parseBodyFragment(article.html) }

    @Test
    fun isAWellFormedChapter() {
        dump()
        val xml = "<div xmlns=\"http://www.w3.org/1999/xhtml\">${article.html}</div>"
        runCatching { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream()) }
            .onFailure { fail("not well-formed: ${it.message}") }
        assertTrue("no article text: ${article.note}", article.wordCount > 0 || article.imageUrls.isNotEmpty())
    }

    @Test
    fun everyInBookLinkLands() {
        val ids = doc.select("[id]").map { it.id() }.toSet()
        val broken = doc.select("a[href^=#]").map { it.attr("href").drop(1) }.filter { it !in ids }
        assertTrue("links to missing ids: $broken", broken.isEmpty())
    }

    @Test
    fun noEmbedOrScriptLeftOver() {
        val leftovers = doc.select("script, style, iframe, form, button, input, svg, noscript, [data-attrs], [data-component-name]")
        assertTrue("left over: ${leftovers.map { it.tagName() }}", leftovers.isEmpty())
    }

    @Test
    fun noEmptyBlocks() {
        val empty = doc.select("p, figure, blockquote, li, h1, h2, h3, h4, h5, h6").filter {
            it.text().isBlank() && it.select("img, br, hr").isEmpty()
        }
        assertTrue("empty: ${empty.map { it.outerHtml().take(80) }}", empty.isEmpty())
    }

    @Test
    fun noPageFurniture() {
        val furniture = doc.select("p, li, h2, h3, h4, h5, a, figcaption, div").filter { el ->
            val text = el.text().trim()
            text.split(' ').size <= 40 && FURNITURE.containsMatchIn(text)
        }
        assertTrue("furniture: ${furniture.map { it.text() }.distinct()}", furniture.isEmpty())
    }

    /** Substack's and Ghost's paid posts say they're only the free part; no free post does. */
    @Test
    fun paidPostsAreTheOnesThePlatformGated() {
        assertEquals("paid post", post.dir.name in PAID.keys, article.paidPost)
        assertEquals("nothing free", PAID[post.dir.name] == true, article.nothingFree)
    }

    /**
     * Each picture comes through tt-rss at the same size: it re-joins srcset candidates with bare
     * commas, which Substack's image addresses contain too. It can add one (an image the feed hid
     * with a style tt-rss strips) and loses YouTube's thumbnails with the players.
     */
    @Test
    fun throughTtrssTheSamePictures() {
        assumeTrue(post.via == "tt-rss")
        val fromFeed = runBlocking { Post(post.dir, post.platform, "feed").extract() }
        // tt-rss percent-encodes "$" in the src it keeps; the srcset it keeps is what's used.
        val viaTtrss = article.imageUrls.map { it.replace("%24", "$") }
        val missing = fromFeed.imageUrls.map { it.replace("%24", "$") }.filter { "i.ytimg.com" !in it && it !in viaTtrss }
        assertTrue("not through tt-rss: $missing", missing.isEmpty())
    }

    @Test
    fun noTrackingPixels() {
        val pixels = article.imageUrls.filter { TRACKER.containsMatchIn(it) }
        assertTrue("tracking pixels: $pixels", pixels.isEmpty())
    }

    /** A YouTube player can't come along into a book; a link to the video, with its thumbnail, does. */
    @Test
    fun youTubeVideosBecomeLinks() {
        val players = YOUTUBE_PLAYER.findAll(post.content.orEmpty()).map { it.groupValues[1] }.toSet()
        assumeTrue(players.isNotEmpty())
        for (id in players) assertTrue("no link to $id", doc.select("a[href=https://www.youtube.com/watch?v=$id]").isNotEmpty())
    }

    /** Longreads' picks point at a story on another site: the story is the article, not the pitch. */
    @Test
    fun linkPostsReadTheStory() {
        assumeTrue(post.storyUrl != null)
        assertFalse("used the pitch: ${article.note}", article.usedFeedContent)
        assertTrue("${article.wordCount} words", article.wordCount > 1000)
    }

    private fun dump() {
        val out = File("build/platform-corpus/${post.platform}").apply { mkdirs() }
        File(out, "${post.dir.name}-${post.via}.html").writeText(
            "<!-- ${article.wordCount} words, ${if (article.usedFeedContent) "feed" else "page"}, note: ${article.note} -->\n${article.html}\n",
        )
    }

    companion object {
        private val FURNITURE = Regex(
            "^(share|subscribe( now)?|(add|leave) a comment:?|upgrade to paid|restack|give a gift subscription|" +
                "read in app|sign up|read more|continue reading|listen to this|get the app|start writing|newsletter)$|" +
                "consider becoming a (free or )?paid subscriber|reader-supported publication|subscribe to .{1,60} to keep reading|" +
                "subscribe to [^.!?]{1,60}:$|newsletter\\b.{0,200}\\bsign up\\.?$",
            RegexOption.IGNORE_CASE,
        )
        private val TRACKER = Regex("/_/stat\\?|pixel\\.wp\\.com|stats\\.wordpress\\.com|feedburner\\.com/~r/")
        private val YOUTUBE_PLAYER = Regex("youtube(?:-nocookie)?\\.com/embed/([A-Za-z0-9_-]{6,})")

        /** The corpus' paid posts, and whether each has next to nothing free. */
        private val PAID = mapOf(
            "www.slowboring.com-cracks-in-the-republican-coalition" to false,
            "www.slowboring.com-math-needs-a-phonics-style-reckoning" to false,
            "www.slowboring.com-tuesday-discussion-post-cc1" to true,
            "www.platformer.news-open-ai-model-release-canceled" to true,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun posts(): List<Array<Any>> {
            val root = File(PlatformCorpusTest::class.java.getResource("/platforms")!!.toURI())
            return root.listFiles()!!.filter { it.isDirectory }.sorted().flatMap { platform ->
                platform.listFiles()!!.filter { it.isDirectory }.sorted().flatMap { dir ->
                    listOf("feed", "tt-rss").map { via -> arrayOf<Any>(Post(dir, platform.name, via), "${platform.name}/${dir.name} via $via") }
                }
            }
        }
    }
}
