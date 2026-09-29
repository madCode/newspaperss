package com.app.newspaperss.core.extract

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.io.IOException
import java.net.URI

/** Where an article's text comes from. */
enum class ContentMode {
    /** The feed's content when it looks like the full text, else the page. */
    AUTO,

    /** The feed is full text: don't fetch pages. */
    FEED,

    /** The feed only has teasers: always fetch the page. */
    PAGE,
}

data class ExtractInput(
    val url: String,
    /** The title the feed gave; preferred over the page's, which often carries a site name. */
    val feedTitle: String,
    /** The item's content from the feed, if any. */
    val feedHtml: String?,
    val feedAuthor: String?,
    val mode: ContentMode = ContentMode.AUTO,
)

data class ExtractedArticle(
    val title: String,
    val author: String?,
    /** Cleaned XHTML fragment, ready for an EPUB chapter. */
    val html: String,
    val wordCount: Int,
    val minutes: Double,
    val usedFeedContent: Boolean,
    /** Shown to the reader above the article, e.g. when the page couldn't be fetched. */
    val note: String?,
    /** Absolute image URLs in [html], in document order. */
    val imageUrls: List<String>,
    /** Words in the feed's content (0 if it had none), for [ArticleExtractor.suggestMode]. */
    val feedWordCount: Int,
    /** Words extracted from the page, or null if the page wasn't fetched or failed. */
    val pageWordCount: Int?,
    /** The site turned the page request away or answered with a bot check. */
    val pageBlocked: Boolean = false,
)

/**
 * Turns a feed item or saved link into a readable article, choosing between
 * the feed's content and the fetched page (see [ContentMode]).
 *
 * It always returns an article: when nothing could be fetched and the feed
 * had no content, the article says so and links to the page, so a source
 * that always fails shows up in the edition instead of silently vanishing.
 */
class ArticleExtractor(private val http: HttpClient) {

    suspend fun extract(input: ExtractInput): ExtractedArticle {
        val feed = input.feedHtml?.takeIf { it.isNotBlank() }
            ?.let { HtmlCleaner.clean(it, input.url, input.feedTitle) }
            // An image with no text is still content: a webcomic's feed item is often just the comic.
            ?.takeIf { it.wordCount > 0 || it.imageUrls.isNotEmpty() }
        val feedWords = feed?.wordCount ?: 0
        val feedAuthor = PageExtractor.cleanAuthor(input.feedAuthor)

        fun fromFeed(clean: CleanResult, note: String?, pageWords: Int?, blocked: Boolean = false) =
            article(input.feedTitle.ifBlank { titleFromUrl(input.url) }, feedAuthor, clean, true, note, feedWords, pageWords, blocked)

        // A FEED source's item with no content still gets its page fetched: better than an empty article.
        if (feed != null && (input.mode == ContentMode.FEED || (input.mode == ContentMode.AUTO && feedWords >= FULL_TEXT_WORDS))) {
            return fromFeed(feed, null, null)
        }

        return when (val page = fetchPage(input)) {
            is PageResult.Failed ->
                if (feed != null) fromFeed(feed, "Couldn't fetch the full article (${page.reason}); showing the feed's version.", null, page.blocked)
                else failed(input, page.reason, page.blocked)
            is PageResult.Fetched -> {
                val words = page.clean.wordCount
                // Little text and no image: a cartoon or comic, where extraction missed the point of
                // the page (often settling on the footer). The feed's images, or the page's main
                // image, are the post.
                if (page.clean.imageUrls.isEmpty() && words < IMAGE_POST_MAX_WORDS) {
                    if (feed != null && feed.imageUrls.isNotEmpty()) return fromFeed(feed, null, words)
                    imagePost(input, page, feed, feedAuthor, feedWords)?.let { return it }
                }
                when {
                    // Extraction that keeps well under the feed's text missed the article; the feed is better.
                    feed != null && words < KEEP_FEED_RATIO * feedWords -> fromFeed(feed, null, words)
                    words == 0 -> failed(input, "no article text found on the page")
                    else -> article(
                        title = input.feedTitle.ifBlank { page.content.title?.takeIf { it.isNotBlank() } ?: titleFromUrl(input.url) },
                        author = feedAuthor ?: page.content.author,
                        clean = page.clean,
                        usedFeed = false,
                        note = null,
                        feedWords = feedWords,
                        pageWords = words,
                    )
                }
            }
        }
    }

    private sealed interface PageResult {
        class Fetched(val content: PageContent, val clean: CleanResult, val url: String) : PageResult
        class Failed(val reason: String, val blocked: Boolean = false) : PageResult
    }

    private suspend fun fetchPage(input: ExtractInput): PageResult {
        val response = try {
            http.get(input.url)
        } catch (e: IOException) {
            return PageResult.Failed("couldn't connect")
        }
        unusable(response)?.let { return it }
        val content = PageExtractor.extract(response.body, response.finalUrl)
        val title = input.feedTitle.ifBlank { content.title.orEmpty() }
        return PageResult.Fetched(content, HtmlCleaner.clean(content.html, response.finalUrl, title), response.finalUrl)
    }

    private fun unusable(response: HttpResponse): PageResult.Failed? {
        if (response.code in BLOCKED_STATUS_CODES) return PageResult.Failed("the site turned the app away (error ${response.code})", blocked = true)
        if (!response.isSuccessful) return PageResult.Failed("error ${response.code}")
        val type = response.contentType?.lowercase()
        if (type != null && "html" !in type && "xml" !in type) return PageResult.Failed("not a web page")
        // Bot checks are small pages served with a 200 status.
        if (response.body.length < CHALLENGE_PAGE_MAX_CHARS) {
            val head = response.body.take(30_000)
            if (CHALLENGE_MARKERS.any { it in head }) return PageResult.Failed("the site asked for a bot check", blocked = true)
        }
        return null
    }

    private fun failed(input: ExtractInput, reason: String, blocked: Boolean = false): ExtractedArticle {
        val link = HtmlCleaner.absoluteUrl(input.url, "")
        val html = if (link != null) {
            "<p>Couldn't fetch this article ($reason). <a href=\"${Entities.escape(link)}\">Read it on the web</a>.</p>"
        } else {
            "<p>Couldn't fetch this article ($reason).</p>"
        }
        return ExtractedArticle(
            title = input.feedTitle.ifBlank { titleFromUrl(input.url) },
            author = PageExtractor.cleanAuthor(input.feedAuthor),
            html = html,
            wordCount = 0,
            minutes = 0.0,
            usedFeedContent = false,
            note = "Couldn't fetch this article.",
            imageUrls = emptyList(),
            feedWordCount = 0,
            pageWordCount = null,
            pageBlocked = blocked,
        )
    }

    /**
     * The page's main image with a caption: the page's own text if it came from the article, else
     * the feed's text or the page's description. Null if the page has no main image.
     */
    private fun imagePost(input: ExtractInput, page: PageResult.Fetched, feed: CleanResult?, feedAuthor: String?, feedWords: Int): ExtractedArticle? {
        val image = page.content.mainImage ?: return null
        val pageText = Jsoup.parse(page.clean.html).text()
        val caption = when {
            pageText.isNotBlank() && page.content.articleText?.contains(pageText.take(ARTICLE_TEXT_PROBE)) == true -> page.clean.html
            feed != null -> feed.html
            else -> page.content.description?.let { "<p>${Entities.escape(it)}</p>" }.orEmpty()
        }
        val title = input.feedTitle.ifBlank { page.content.title?.takeIf { it.isNotBlank() } ?: titleFromUrl(input.url) }
        val clean = HtmlCleaner.clean(image + caption, page.url, title)
        if (clean.imageUrls.isEmpty()) return null
        return article(title, feedAuthor ?: page.content.author, clean, false, null, feedWords, page.clean.wordCount)
    }

    private fun article(
        title: String, author: String?, clean: CleanResult, usedFeed: Boolean, note: String?, feedWords: Int, pageWords: Int?,
        blocked: Boolean = false,
    ) = ExtractedArticle(
        title = title.replace(WHITESPACE, " ").trim(),
        author = author,
        html = clean.html,
        wordCount = clean.wordCount,
        minutes = ReadingTime.minutes(clean.wordCount),
        usedFeedContent = usedFeed,
        note = note,
        imageUrls = clean.imageUrls,
        feedWordCount = feedWords,
        pageWordCount = pageWords,
        pageBlocked = blocked,
    )

    companion object {
        /** Feed content with at least this many words is taken as the full text in [ContentMode.AUTO]. */
        const val FULL_TEXT_WORDS = 300
        private const val KEEP_FEED_RATIO = 0.7
        private const val IMAGE_POST_MAX_WORDS = 150
        private const val ARTICLE_TEXT_PROBE = 60
        private const val TEASER_RATIO = 2.0
        private const val CHALLENGE_PAGE_MAX_CHARS = 150 * 1024
        private val BLOCKED_STATUS_CODES = setOf(401, 403, 429, 503)
        private val CHALLENGE_MARKERS = listOf(
            "<title>Just a moment...</title>", "<title>Verifying Device</title>", "cf-browser-verification",
            "challenge-platform", "_Incapsula_Resource", "captcha-delivery.com", "px-captcha",
            "Enable JavaScript and cookies to continue",
        )
        private val WHITESPACE = Regex("\\s+")

        /**
         * The per-source check: given one article's feed and page word
         * counts, the mode the source should use, or null if this article
         * doesn't tell. A page with at least twice the feed's words means
         * the feed is a teaser; a page with under 0.7x means extraction
         * does worse than the feed.
         */
        fun suggestMode(feedWords: Int, pageWords: Int?): ContentMode? = when {
            pageWords == null || feedWords == 0 -> null
            pageWords >= TEASER_RATIO * feedWords -> ContentMode.PAGE
            pageWords < KEEP_FEED_RATIO * feedWords -> ContentMode.FEED
            else -> null
        }

        /** A readable stand-in title: ".../2026/09/why-genre-matters/123" becomes "Why genre matters (example.com)". */
        fun titleFromUrl(url: String): String {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url
            val host = uri.host.orEmpty().removePrefix("www.")
            val slug = uri.path.orEmpty().split('/')
                .lastOrNull { it.isNotEmpty() && !it.all { c -> c.isDigit() || !c.isLetterOrDigit() } }
                ?.replace(Regex("\\.[a-z]+$"), "")
                .orEmpty()
            val words = slug.split(Regex("[-_+]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return host.ifEmpty { url }
            val text = words.joinToString(" ").replaceFirstChar { it.uppercase() }
            return if (host.isEmpty()) text else "$text ($host)"
        }
    }
}
