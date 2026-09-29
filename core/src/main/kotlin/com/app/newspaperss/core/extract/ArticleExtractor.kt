package com.app.newspaperss.core.extract

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
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
            ?.takeIf { it.wordCount > 0 }
        val feedWords = feed?.wordCount ?: 0
        val feedAuthor = PageExtractor.cleanAuthor(input.feedAuthor)

        fun fromFeed(clean: CleanResult, note: String?, pageWords: Int?) =
            article(input.feedTitle.ifBlank { titleFromUrl(input.url) }, feedAuthor, clean, true, note, feedWords, pageWords)

        // A FEED source's item with no content still gets its page fetched: better than an empty article.
        if (feed != null && (input.mode == ContentMode.FEED || (input.mode == ContentMode.AUTO && feedWords >= FULL_TEXT_WORDS))) {
            return fromFeed(feed, null, null)
        }

        return when (val page = fetchPage(input)) {
            is PageResult.Failed ->
                if (feed != null) fromFeed(feed, "Couldn't fetch the full article (${page.reason}); showing the feed's version.", null)
                else failed(input, page.reason)
            is PageResult.Fetched -> {
                val words = page.clean.wordCount
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
        class Fetched(val content: PageContent, val clean: CleanResult) : PageResult
        class Failed(val reason: String) : PageResult
    }

    private suspend fun fetchPage(input: ExtractInput): PageResult {
        val response = try {
            http.get(input.url)
        } catch (e: IOException) {
            return PageResult.Failed("couldn't connect")
        }
        blockedReason(response)?.let { return PageResult.Failed(it) }
        val content = PageExtractor.extract(response.body, response.finalUrl)
        val title = input.feedTitle.ifBlank { content.title.orEmpty() }
        return PageResult.Fetched(content, HtmlCleaner.clean(content.html, response.finalUrl, title))
    }

    private fun blockedReason(response: HttpResponse): String? {
        if (response.code in BLOCKED_STATUS_CODES) return "the site turned the app away (error ${response.code})"
        if (!response.isSuccessful) return "error ${response.code}"
        val type = response.contentType?.lowercase()
        if (type != null && "html" !in type && "xml" !in type) return "not a web page"
        // Bot checks are small pages served with a 200 status.
        if (response.body.length < CHALLENGE_PAGE_MAX_CHARS) {
            val head = response.body.take(30_000)
            if (CHALLENGE_MARKERS.any { it in head }) return "the site asked for a bot check"
        }
        return null
    }

    private fun failed(input: ExtractInput, reason: String): ExtractedArticle {
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
        )
    }

    private fun article(
        title: String, author: String?, clean: CleanResult, usedFeed: Boolean, note: String?, feedWords: Int, pageWords: Int?,
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
    )

    companion object {
        /** Feed content with at least this many words is taken as the full text in [ContentMode.AUTO]. */
        const val FULL_TEXT_WORDS = 300
        private const val KEEP_FEED_RATIO = 0.7
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
