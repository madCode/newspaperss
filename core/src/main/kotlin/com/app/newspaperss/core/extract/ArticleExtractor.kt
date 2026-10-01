package com.app.newspaperss.core.extract

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.net.ErrorAnswers
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
    /**
     * The page [feedHtml] is from, when that isn't [url]: a link post, whose [url] is the story it
     * points to (see [com.app.newspaperss.core.feed.LinkPosts]).
     */
    val feedUrl: String? = null,
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
    /** The page was tried and couldn't be read for another reason, or null if it was read or not tried. */
    val pageFailure: PageFailure? = null,
    /** The article's language as a BCP 47 tag, if the text or the page says. */
    val language: String? = null,
    /** The name of the site the story is from, if its page was read and says. */
    val siteName: String? = null,
    /**
     * A link post whose page turned out not to be the story it pitched (see
     * [com.app.newspaperss.core.feed.LinkPosts.isTheStory]): the article is the item alone, and
     * belongs to the item's own page.
     */
    val notTheStory: Boolean = false,
)

/** Why a page couldn't be read, when the site didn't turn the app away. */
enum class PageFailure {
    /** Worth trying again: no connection, a server error. */
    TRANSIENT,
    /** It will be the same next time: gone, not a web page, too large to parse. */
    PERMANENT,
}

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
            ?.let { HtmlCleaner.clean(it, input.feedUrl ?: input.url, input.feedTitle) }
            // An image with no text is still content: a webcomic's feed item is often just the comic.
            ?.takeIf { it.wordCount > 0 || it.imageUrls.isNotEmpty() }
        val feedWords = feed?.wordCount ?: 0
        val feedAuthor = PageExtractor.cleanAuthor(input.feedAuthor)

        fun fromFeed(
            clean: CleanResult, note: String?, pageWords: Int?, blocked: Boolean = false, declaredLanguage: String? = null, failure: PageFailure? = null,
            siteName: String? = null,
        ) = article(input.feedTitle.ifBlank { titleFromUrl(input.url) }, feedAuthor, clean, true, note, feedWords, pageWords, blocked, declaredLanguage, failure, siteName)

        // A FEED source's item with no content still gets its page fetched: better than an empty article.
        if (feed != null && (input.mode == ContentMode.FEED || (input.mode == ContentMode.AUTO && feedWords >= FULL_TEXT_WORDS))) {
            return fromFeed(feed, null, null)
        }

        return when (val page = fetchPage(input)) {
            is PageResult.Failed ->
                if (feed != null) fromFeed(feed, "Couldn't fetch the full article (${page.reason}); showing the feed's version.", null, page.blocked, failure = page.failure)
                else failed(input, page.reason, page.blocked, page.failure)
            is PageResult.Fetched -> {
                val words = page.clean.wordCount
                if (input.feedUrl != null && feed != null) return linkPost(input, page, feedWords) { note, siteName ->
                    fromFeed(feed, note, words, declaredLanguage = page.content.language, siteName = siteName)
                }
                // A cartoon or comic: extraction found little text and no image. Only on strong
                // signs, so a short brief doesn't get a stray picture: a feed item that's
                // essentially just the image, or page text that isn't from the article at all
                // (extraction settled on the footer) with a real image in the article.
                if (page.clean.imageUrls.isEmpty() && words < IMAGE_POST_MAX_WORDS) {
                    val missed = missedTheArticle(page)
                    if (feed != null && feed.imageUrls.isNotEmpty() && feedWords <= IMAGE_CAPTION_WORDS && (missed || words < FEED_IMAGE_PAGE_WORDS)) {
                        // The page's own comic beats the feed's image, which is often a thumbnail.
                        page.content.comicImage?.let { comic ->
                            imagePost(input, page, comic, feed, feedAuthor, feedWords, feedImagesAreThumbnails = true)?.let { return it }
                        }
                        return fromFeed(feed, null, words, declaredLanguage = page.content.language)
                    }
                    if (missed) {
                        val image = page.content.mainImage ?: page.content.comicImage
                        if (image != null) imagePost(input, page, image, feed, feedAuthor, feedWords)?.let { return it }
                    }
                }
                when {
                    // Extraction that keeps well under the feed's text missed the article; the feed is better.
                    feed != null && words < KEEP_FEED_RATIO * feedWords -> fromFeed(feed, null, words, declaredLanguage = page.content.language)
                    words == 0 -> failed(input, "no article text found on the page")
                    else -> article(
                        title = input.feedTitle.ifBlank { page.content.title?.takeIf { it.isNotBlank() } ?: titleFromUrl(input.url) },
                        author = feedAuthor ?: page.content.author,
                        clean = page.clean,
                        usedFeed = false,
                        note = null,
                        feedWords = feedWords,
                        pageWords = words,
                        declaredLanguage = page.content.language,
                    )
                }
            }
        }
    }

    /**
     * A link post's story page, or its pitch (from [pitch], given a note and the story's site name)
     * when the page isn't clearly the story: a page titled for something else means the post wasn't
     * pointing at a story after all, and a page with little more text than the pitch is a paywall
     * preview.
     */
    private fun linkPost(
        input: ExtractInput, page: PageResult.Fetched, feedWords: Int, pitch: (note: String?, siteName: String?) -> ExtractedArticle,
    ): ExtractedArticle {
        if (!LinkPosts.isTheStory(input.feedTitle, page.content.title, page.url)) return pitch(null, null).copy(notTheStory = true)
        val words = page.clean.wordCount
        if (words < TEASER_RATIO * feedWords) return pitch(SHORT_STORY_NOTE, page.content.siteName)
        return article(
            title = input.feedTitle.ifBlank { page.content.title?.takeIf { it.isNotBlank() } ?: titleFromUrl(input.url) },
            // The item's author wrote the pitch, not the story.
            author = page.content.author,
            clean = page.clean,
            usedFeed = false,
            note = null,
            feedWords = feedWords,
            pageWords = words,
            declaredLanguage = page.content.language,
            siteName = page.content.siteName,
        )
    }

    private sealed interface PageResult {
        class Fetched(val content: PageContent, val clean: CleanResult, val url: String) : PageResult
        class Failed(val reason: String, val blocked: Boolean = false, permanent: Boolean = false) : PageResult {
            val failure: PageFailure? = when {
                blocked -> null
                permanent -> PageFailure.PERMANENT
                else -> PageFailure.TRANSIENT
            }
        }
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
        if (response.code in ErrorAnswers.BLOCKED) return PageResult.Failed("the site turned the app away (error ${response.code})", blocked = true)
        if (!response.isSuccessful) return PageResult.Failed("error ${response.code}", permanent = response.code in GONE_STATUS_CODES)
        val type = response.contentType?.lowercase()
        if (type != null && "html" !in type && "xml" !in type) return PageResult.Failed("not a web page", permanent = true)
        // Parsed, a page takes several times its size in memory, and Readability copies it. Real
        // articles are well under this; the feed's text is the better bet for anything bigger.
        if (response.body.length > MAX_PAGE_CHARS) return PageResult.Failed("the page is too large", permanent = true)
        if (isBotCheck(response.body)) return PageResult.Failed("the site asked for a bot check", blocked = true)
        return null
    }

    private fun failed(input: ExtractInput, reason: String, blocked: Boolean = false, failure: PageFailure? = null): ExtractedArticle {
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
            pageFailure = failure,
        )
    }

    /**
     * The page has an article, and the text extraction found isn't from it (it settled on the
     * footer). An empty page isn't counted: it stays a failure, with its link to the page.
     */
    private fun missedTheArticle(page: PageResult.Fetched): Boolean {
        val articleText = page.content.articleText?.let(::normalized) ?: return false
        val pageText = normalized(Jsoup.parse(page.clean.html).text())
        if (pageText.isEmpty()) return false
        // A stretch from the middle: the cleaner may have dropped a label or two at the start.
        val probe = pageText.substring(pageText.length / 4).take(ARTICLE_TEXT_PROBE)
        return probe !in articleText
    }

    private fun normalized(text: String) = text.lowercase().replace(NOT_LETTERS, "")

    /**
     * [image] from the page, captioned by the feed's content or else the page's description; null
     * if it can't be used. With [feedImagesAreThumbnails], the feed's images are left out: they're
     * smaller copies of [image].
     */
    private fun imagePost(
        input: ExtractInput, page: PageResult.Fetched, image: String, feed: CleanResult?, feedAuthor: String?, feedWords: Int,
        feedImagesAreThumbnails: Boolean = false,
    ): ExtractedArticle? {
        val fromFeed = feed?.html?.let { html ->
            if (!feedImagesAreThumbnails) return@let html
            // Only the images: a <figcaption> is often the joke. HtmlCleaner drops what's left empty.
            // Not a caption the feed made from the thumbnail's hover text that the comic has too:
            // left here, it would sit apart from the comic, which then wouldn't get its own.
            val comicTitles = Jsoup.parseBodyFragment(image).select("img[title]").map { normalized(it.attr("title")) }.toSet()
            Jsoup.parseBodyFragment(html).body().apply {
                select("img, picture").remove()
                select("figcaption").filter { normalized(it.text()) in comicTitles }.forEach { it.remove() }
            }.html()
        }?.takeIf { Jsoup.parse(it).text().isNotBlank() || !feedImagesAreThumbnails }
        val caption = fromFeed ?: page.content.description?.let { "<p>${Entities.escape(it)}</p>" }.orEmpty()
        val title = input.feedTitle.ifBlank { page.content.title?.takeIf { it.isNotBlank() } ?: titleFromUrl(input.url) }
        val clean = HtmlCleaner.clean(image + caption, page.url, title)
        if (clean.imageUrls.isEmpty()) return null
        return article(title, feedAuthor ?: page.content.author, clean, false, null, feedWords, page.clean.wordCount, declaredLanguage = page.content.language)
    }

    private fun article(
        title: String, author: String?, clean: CleanResult, usedFeed: Boolean, note: String?, feedWords: Int, pageWords: Int?,
        blocked: Boolean = false, declaredLanguage: String? = null, failure: PageFailure? = null, siteName: String? = null,
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
        pageFailure = failure,
        language = LanguageDetector.detect(title + "\n" + Jsoup.parse(clean.html).text(), declaredLanguage),
        siteName = siteName,
    )

    companion object {
        /** Feed content with at least this many words is taken as the full text in [ContentMode.AUTO]. */
        const val FULL_TEXT_WORDS = 300
        const val SHORT_STORY_NOTE = "Only the summary: the story's own page had little more to read."
        private const val KEEP_FEED_RATIO = 0.7
        private const val IMAGE_POST_MAX_WORDS = 150
        private const val IMAGE_CAPTION_WORDS = 25
        private const val FEED_IMAGE_PAGE_WORDS = 80
        private val NOT_LETTERS = Regex("[^\\p{L}\\p{N}]")
        private const val ARTICLE_TEXT_PROBE = 60
        private const val TEASER_RATIO = 2.0
        private const val CHALLENGE_PAGE_MAX_CHARS = 150 * 1024
        private const val MAX_PAGE_CHARS = 5 * 1024 * 1024
        // 410 only: a 404 is often a site having a bad day.
        private val GONE_STATUS_CODES = setOf(410)
        private val CHALLENGE_MARKERS = listOf(
            "<title>Just a moment...</title>", "<title>Verifying Device</title>", "cf-browser-verification",
            "challenge-platform", "_Incapsula_Resource", "captcha-delivery.com", "px-captcha",
            "Enable JavaScript and cookies to continue",
            // Fastly's bot challenge (Le Monde): its assets load from /_fs-ch-….
            "<title>Client Challenge</title>", "/_fs-ch-",
        )
        private val WHITESPACE = Regex("\\s+")

        /** Whether a page is a bot check rather than the page asked for: they're small pages served with a 200 status. */
        fun isBotCheck(body: String): Boolean =
            body.length < CHALLENGE_PAGE_MAX_CHARS && body.take(30_000).let { head -> CHALLENGE_MARKERS.any { it in head } }

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
