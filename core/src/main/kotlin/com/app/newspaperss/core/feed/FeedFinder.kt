package com.app.newspaperss.core.feed

import com.app.newspaperss.core.net.HttpClient
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

data class FoundFeed(val url: String, val title: String?)

sealed interface FindResult {
    data class Found(val feeds: List<FoundFeed>) : FindResult
    /** @property page the page itself, when it loaded but offers no feed: it can still be saved to read later. */
    data class NotFound(val reason: String, val page: String? = null) : FindResult
}

/**
 * Turns whatever a person types ("example.com", a page, a feed URL) into
 * feed URLs: the address itself if it is a feed, else the feeds the page
 * advertises, else the usual feed paths on the site.
 */
class FeedFinder(private val http: HttpClient) {

    /** What an error answer means for someone adding a site, with the code kept for anyone who asks. */
    private fun refused(url: String, code: Int): String = when (code) {
        404, 410 -> "There's nothing at $url. Check the address."
        // The codes ArticleExtractor counts as blocked: a Cloudflare wall answers 503, a paywall 402.
        401, 402, 403, 429, 503 -> "$url turned newspapeRSS away (error $code). Some sites block apps: try its feed or RSS link if it lists one, or try again later."
        in 500..599 -> "$url isn't working right now (error $code). Try again later."
        else -> "$url answered with error $code."
    }

    suspend fun find(input: String): FindResult {
        val url = normalize(input) ?: return FindResult.NotFound("That doesn't look like a web address.")
        val response = try {
            http.get(url)
        } catch (e: IOException) {
            return FindResult.NotFound("Couldn't reach $url. Check the address and your connection.")
        }
        if (!response.isSuccessful) return FindResult.NotFound(refused(url, response.code))

        if (FeedParser.looksLikeFeed(response.body)) {
            val title = runCatching { FeedParser.parse(response.body, response.finalUrl).title }.getOrNull()
            return FindResult.Found(listOf(FoundFeed(response.finalUrl, title)))
        }

        val advertised = advertisedFeeds(response.body, response.finalUrl)
        if (advertised.isNotEmpty()) return FindResult.Found(advertised)

        // Some sites only link their feed from the page (a webcomic's "RSS" button), with no
        // <link rel="alternate"> in the head.
        for (candidate in linkedFeeds(response.body, response.finalUrl).take(MAX_LINKED_TRIES)) {
            val r = try { http.get(candidate) } catch (_: IOException) { continue }
            if (r.isSuccessful && FeedParser.looksLikeFeed(r.body)) {
                val title = runCatching { FeedParser.parse(r.body, r.finalUrl).title }.getOrNull()
                return FindResult.Found(listOf(FoundFeed(r.finalUrl, title)))
            }
        }

        for (path in COMMON_PATHS) {
            val candidate = resolveUrl(response.finalUrl, path)
            val r = try { http.get(candidate) } catch (_: IOException) { continue }
            if (r.isSuccessful && FeedParser.looksLikeFeed(r.body)) {
                val title = runCatching { FeedParser.parse(r.body, r.finalUrl).title }.getOrNull()
                return FindResult.Found(listOf(FoundFeed(r.finalUrl, title)))
            }
        }
        return FindResult.NotFound("No feed found at $url.", page = response.finalUrl.takeIf { articleLike(url, it, response.contentType) })
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
        fun host(u: URI) = u.host?.lowercase()?.removePrefix("www.")
        if (host(a) == null || host(a) != host(l)) return false
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
            val host = runCatching { URI(pageUrl).host?.removePrefix("www.") }.getOrNull() ?: return emptyList()
            return Jsoup.parse(html, pageUrl).select("a[href]").map { it.absUrl("href") }
                .filter { href ->
                    val uri = runCatching { URI(href) }.getOrNull() ?: return@filter false
                    val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase()
                    val segments = uri.path.orEmpty().lowercase().split('/')
                    uri.host?.removePrefix("www.") == host && last in FEED_LINK_NAMES && "comments" !in segments
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
