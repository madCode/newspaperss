package com.app.newspaperss.core.feed

import com.app.newspaperss.core.net.siteHost
import com.app.newspaperss.core.net.ErrorAnswers
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

data class FoundFeed(val url: String, val title: String?)

sealed interface FindResult {
    /**
     * @property page the page typed, when it's an article rather than the feed itself or a site's
     *   front page: something to save to read later if the feed can't be followed after all.
     */
    data class Found(val feeds: List<FoundFeed>, val page: String? = null) : FindResult
    /** @property page the page itself, when it loaded but offers no feed: it can still be saved to read later. */
    data class NotFound(val reason: String, val page: String? = null) : FindResult
}

/**
 * Turns whatever a person types ("example.com", a page, a feed URL) into
 * feed URLs: the address itself if it is a feed, else the feeds the page
 * advertises, else the usual feed paths on the site.
 */
class FeedFinder(private val http: HttpClient) {

    suspend fun find(input: String): FindResult {
        val url = normalize(input) ?: return FindResult.NotFound("That doesn't look like a web address.")
        val response = try {
            http.getFeed(url)
        } catch (e: IOException) {
            return FindResult.NotFound("Couldn't reach $url. Check the address and your connection.")
        }
        if (!response.isSuccessful) return FindResult.NotFound(ErrorAnswers.message(response.code, url))

        if (FeedParser.looksLikeFeed(response.body)) {
            return feedAt(response) ?: FindResult.NotFound("$url is too large a feed to read.")
        }
        // Only a feed may come cut short: a page that big is a video or a file, not a site.
        if (response.truncated) return FindResult.NotFound("$url is too large to be a web page or a feed.")

        val page = response.finalUrl.takeIf { articleLike(url, it, response.contentType) }
        val advertised = advertisedFeeds(response.body, response.finalUrl)
        if (advertised.isNotEmpty()) return FindResult.Found(advertised, page)

        // Some sites only link their feed from the page (a webcomic's "RSS" button), with no
        // <link rel="alternate"> in the head.
        for (candidate in linkedFeeds(response.body, response.finalUrl).take(MAX_LINKED_TRIES)) {
            val r = try { http.getFeed(candidate) } catch (_: IOException) { continue }
            if (r.isSuccessful && FeedParser.looksLikeFeed(r.body)) {
                feedAt(r, page)?.let { return it }
            }
        }

        for (path in COMMON_PATHS) {
            val candidate = resolveUrl(response.finalUrl, path)
            val r = try { http.getFeed(candidate) } catch (_: IOException) { continue }
            if (r.isSuccessful && FeedParser.looksLikeFeed(r.body)) {
                feedAt(r, page)?.let { return it }
            }
        }
        return FindResult.NotFound("No feed found at $url.", page = page)
    }

    /** [response] as the feed found, titled if it reads; null if it's too large to read even in part. */
    private fun feedAt(response: HttpResponse, page: String? = null): FindResult.Found? {
        val title = try {
            FeedParser.parse(response.body, response.finalUrl, response.truncated).title
        } catch (_: FeedTooLargeException) {
            return null
        } catch (_: FeedParseException) {
            null
        }
        return FindResult.Found(listOf(FoundFeed(response.finalUrl, title)), page)
    }

    /**
     * Whether a page with no feed is worth saving to read later: an article, not a site's front
     * page (it would make an edition of navigation), and not somewhere a redirect took it on
     * another site (a paywall's sign-in page).
     */
    private fun articleLike(asked: String, landed: String, contentType: String?): Boolean {
        if (contentType != null && "html" !in contentType.lowercase()) return false
        val a = runCatching { URI(asked) }.getOrNull() ?: return false
        val l = runCatching { URI(landed) }.getOrNull() ?: return false
        if (siteHost(a) == null || siteHost(a) != siteHost(l)) return false
        return !l.path.isNullOrEmpty() && l.path != "/"
    }

    companion object {
        private val FEED_TYPES = setOf(
            "application/rss+xml", "application/atom+xml", "application/feed+json",
            "application/json", "application/rdf+xml", "text/xml", "application/xml",
        )

        private const val MAX_LINKED_TRIES = 3
        private val FEED_LINK_NAMES = setOf("rss", "feed", "atom", "rss.xml", "feed.xml", "atom.xml", "index.xml", "rss2")

        /**
         * Links on the page whose last path segment names a feed ("/comic/rss", "/feed.xml",
         * "/series/rss?title_no=1"), on the page's own site. Shortest path first, so the site's
         * feed comes before a tag's or a category's; comment feeds are left out.
         */
        internal fun linkedFeeds(html: String, pageUrl: String): List<String> {
            val host = runCatching { siteHost(URI(pageUrl)) }.getOrNull() ?: return emptyList()
            return Jsoup.parse(html, pageUrl).select("a[href]").map { it.absUrl("href") }
                .filter { href ->
                    val uri = runCatching { URI(href) }.getOrNull() ?: return@filter false
                    val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase()
                    val segments = uri.path.orEmpty().lowercase().split('/')
                    siteHost(uri) == host && last in FEED_LINK_NAMES && "comments" !in segments
                }
                .distinct()
                .sortedBy { runCatching { URI(it).path.orEmpty().count { c -> c == '/' } }.getOrDefault(Int.MAX_VALUE) }
        }

        // Absolute paths: a feed usually lives at the site root, not under the page typed.
        internal val COMMON_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/feed.json")

        internal fun normalize(input: String): String? {
            val t = input.trim()
            if (t.isEmpty() || t.contains(' ')) return null
            val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
            if (!withScheme.startsWith("http://") && !withScheme.startsWith("https://")) return null
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
            return if (host.contains('.') || host.startsWith("localhost")) withScheme else null
        }

        internal fun advertisedFeeds(html: String, pageUrl: String): List<FoundFeed> =
            Jsoup.parse(html, pageUrl).select("link[rel~=(?i)alternate][href]")
                .filter { it.attr("type").lowercase().substringBefore(';').trim() in FEED_TYPES }
                // Comment feeds are rarely what someone subscribing to a site wants.
                .filterNot { it.attr("title").contains("comments", ignoreCase = true) }
                .map { FoundFeed(it.absUrl("href"), it.attr("title").ifBlank { null }) }
                .distinctBy { it.url }
    }
}
