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

        // Some sites advertise their feed only on their blog's own page (HubSpot's /news/rss.xml
        // in /news's head): from a front page, look there too.
        mainSection(response.body, response.finalUrl)?.let { section ->
            val r = try { http.get(section) } catch (_: IOException) { null }
            // On this site still: a section can redirect to someone else's (a blog on Substack).
            val sameSite = r != null && runCatching { siteHost(URI(r.finalUrl)) == siteHost(URI(response.finalUrl)) }.getOrDefault(false)
            if (r != null && r.isSuccessful && sameSite) {
                val found = advertisedFeeds(r.body, r.finalUrl)
                if (found.isNotEmpty()) return FindResult.Found(found, page)
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
        private const val MIN_SECTION_LINKS = 10
        // Folders of files and listings, never the site's writing; and sections whose feed, if
        // any, is of something else (products, events, a podcast's episodes).
        private val NOT_SECTIONS = setOf(
            "hubfs", "wp-content", "wp-includes", "wp-json", "assets", "static", "cdn-cgi", "_next", "images", "img", "css", "js", "fonts",
            "tag", "tags", "category", "categories", "author", "authors", "page", "search", "about", "contact", "login", "account",
            "shop", "store", "products", "collections", "cart", "events", "episodes", "podcast", "podcasts", "video", "videos",
        )

        /**
         * The section of the site most of a front page's own links go into ("/news", where 143 of
         * a magazine's links point), as an address; null on any other page, or with no clear one.
         * Not a year (date-style addresses) or a language ("/fr"), which aren't sections.
         */
        internal fun mainSection(html: String, pageUrl: String): String? {
            val page = runCatching { URI(pageUrl) }.getOrNull() ?: return null
            if (!page.path.isNullOrEmpty() && page.path != "/") return null
            val host = siteHost(page) ?: return null
            // As written, to fetch: a server can tell "/News" from "/news", and "%3F" from "?".
            val segments = Jsoup.parse(html, pageUrl).select("a[href]").mapNotNull { a ->
                val link = runCatching { URI(a.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
                if (siteHost(link) != host) return@mapNotNull null
                link.rawPath.orEmpty().split('/').getOrNull(1)?.takeIf { it.isNotEmpty() }
            }
            if (segments.isEmpty()) return null
            val counts = segments.filter { s ->
                '.' !in s && s.lowercase() !in NOT_SECTIONS && !s.all(Char::isDigit) && s.length > 2
            }.groupBy { it.lowercase() }
            val (_, best) = counts.maxByOrNull { it.value.size } ?: return null
            // A clear majority of the page's links, not just the biggest of many.
            if (best.size < MIN_SECTION_LINKS || best.size * 2 < segments.size) return null
            return resolveUrl(pageUrl, "/${best.first()}")
        }
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

        /**
         * The feeds a page's `<link>`s name: rel="alternate" with a feed's type, or HTML's
         * rel="feed", whose type is often left out (tt-rss reads both).
         */
        internal fun advertisedFeeds(html: String, pageUrl: String): List<FoundFeed> =
            Jsoup.parse(html, pageUrl).select("link[href]")
                .filter { link ->
                    val rel = link.attr("rel").lowercase().split(Regex("\\s+"))
                    "feed" in rel || "alternate" in rel && link.attr("type").lowercase().substringBefore(';').trim() in FEED_TYPES
                }
                // Comment feeds are rarely what someone subscribing to a site wants, and WordPress's
                // REST API (type application/json) is a page's data, not a feed.
                .filterNot { it.attr("title").contains("comments", ignoreCase = true) || "/wp-json/" in it.attr("href") }
                .map { FoundFeed(it.absUrl("href"), it.attr("title").ifBlank { null }) }
                .distinctBy { it.url }
    }
}
