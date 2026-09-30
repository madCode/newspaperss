package com.app.newspaperss.core.feed

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FeedFinderTest {
    private class FakeHttp(private val pages: Map<String, String>, private val codes: Map<String, Int> = emptyMap()) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse {
            requested += url
            if (url.contains("unreachable")) throw IOException("no route")
            codes[url]?.let { return HttpResponse(it, url, "text/html", "error") }
            val body = pages[url] ?: return HttpResponse(404, url, "text/html", "not found")
            return HttpResponse(200, url, null, body)
        }
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
        override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("not used")
    }

    private val feedXml = "<rss version=\"2.0\"><channel><title>Site feed</title></channel></rss>"

    @Test
    fun bareDomainWithAdvertisedFeeds() = runTest {
        val page = """
            <html><head>
              <link rel="alternate" type="application/rss+xml" title="Posts" href="/feed.xml">
              <link rel="alternate" type="application/rss+xml" title="Comments Feed" href="/comments/feed">
              <link rel="alternate" type="application/atom+xml" href="https://cdn.example.com/atom">
              <link rel="alternate" hreflang="fr" href="/fr/">
            </head></html>
        """
        val http = FakeHttp(mapOf("https://example.com" to page))
        val result = FeedFinder(http).find("  example.com ") as FindResult.Found
        assertEquals(
            listOf(FoundFeed("https://example.com/feed.xml", "Posts"), FoundFeed("https://cdn.example.com/atom", null)),
            result.feeds,
        )
    }

    @Test
    fun feedUrlItself() = runTest {
        val http = FakeHttp(mapOf("https://example.com/rss" to feedXml))
        val result = FeedFinder(http).find("https://example.com/rss") as FindResult.Found
        assertEquals(listOf(FoundFeed("https://example.com/rss", "Site feed")), result.feeds)
    }

    @Test
    fun fallsBackToCommonPathsAtTheSiteRoot() = runTest {
        val http = FakeHttp(mapOf("https://example.com/about" to "<html></html>", "https://example.com/index.xml" to feedXml))
        val result = FeedFinder(http).find("example.com/about") as FindResult.Found
        assertEquals("https://example.com/index.xml", result.feeds.single().url)
        assertTrue(http.requested.contains("https://example.com/feed"))
    }

    @Test
    fun anErrorAnswerSaysWhatItMeansAndWhatToTry() = runTest {
        val finder = FeedFinder(FakeHttp(emptyMap(), mapOf("https://blocked.example" to 403, "https://down.example" to 502, "https://odd.example" to 418)))
        assertEquals(
            "https://blocked.example turned newspapeRSS away (error 403). Some sites block apps: try its feed or RSS link if it lists one, or try again later.",
            (finder.find("blocked.example") as FindResult.NotFound).reason,
        )
        assertEquals("https://down.example isn't working right now (error 502). Try again later.", (finder.find("down.example") as FindResult.NotFound).reason)
        assertEquals("https://odd.example answered with error 418.", (finder.find("odd.example") as FindResult.NotFound).reason)
    }

    @Test
    fun explainsFailures() = runTest {
        val finder = FeedFinder(FakeHttp(mapOf("https://example.com" to "<html></html>")))
        assertTrue((finder.find("not a url") as FindResult.NotFound).reason.contains("web address"))
        assertTrue((finder.find("unreachable.example") as FindResult.NotFound).reason.contains("reach"))
        assertTrue((finder.find("missing.example") as FindResult.NotFound).reason.contains("Check the address"))
        assertTrue((finder.find("example.com") as FindResult.NotFound).reason.contains("No feed"))
    }

    @Test
    fun normalize() {
        assertEquals("https://example.com", FeedFinder.normalize("example.com"))
        assertEquals("http://example.com/x", FeedFinder.normalize("http://example.com/x"))
        assertNull(FeedFinder.normalize("ftp://example.com"))
        assertNull(FeedFinder.normalize("hello"))
        assertNull(FeedFinder.normalize(""))
    }

    @Test
    fun aFeedLinkedOnlyFromThePageIsFound() = runTest {
        // A webcomic's RSS button, with no <link rel="alternate"> in the head.
        val page = "<html><body><a href=\"/archive\">Archive</a><a href=\"https://other.example/rss\">Someone else</a>" +
            "<a href=\"/comic/rss\">RSS</a></body></html>"
        val http = FakeHttp(mapOf("https://comic.example" to page, "https://comic.example/comic/rss" to feedXml))

        val result = FeedFinder(http).find("comic.example") as FindResult.Found

        assertEquals(listOf("https://comic.example/comic/rss"), result.feeds.map { it.url })
        assertTrue("another site's feed isn't tried", "https://other.example/rss" !in http.requested)
    }

    @Test
    fun linkedFeedsKeepTheirQuery() {
        val page = "<a href=\"https://www.webtoons.com/en/comedy/princess/rss?title_no=1537\">RSS</a>"
        assertEquals(
            listOf("https://www.webtoons.com/en/comedy/princess/rss?title_no=1537"),
            FeedFinder.linkedFeeds(page, "https://www.webtoons.com/en/comedy/princess/list?title_no=1537"),
        )
    }

    @Test
    fun theSitesOwnLinkedFeedComesBeforeATagsOrTheComments() {
        val page = "<a href=\"/comments/feed\">Comments</a><a href=\"/tag/cats/feed\">Cats</a><a href=\"/feed\">Posts</a>"
        assertEquals(listOf("https://blog.example/feed", "https://blog.example/tag/cats/feed"), FeedFinder.linkedFeeds(page, "https://blog.example/"))
    }
}
