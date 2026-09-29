package com.app.newspaperss.core.feed

import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FeedFinderTest {
    private class FakeHttp(private val pages: Map<String, String>) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse {
            requested += url
            if (url.contains("unreachable")) throw IOException("no route")
            val body = pages[url] ?: return HttpResponse(404, url, "text/html", "not found")
            return HttpResponse(200, url, null, body)
        }
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
    fun explainsFailures() = runTest {
        val finder = FeedFinder(FakeHttp(mapOf("https://example.com" to "<html></html>")))
        assertTrue((finder.find("not a url") as FindResult.NotFound).reason.contains("web address"))
        assertTrue((finder.find("unreachable.example") as FindResult.NotFound).reason.contains("reach"))
        assertTrue((finder.find("missing.example") as FindResult.NotFound).reason.contains("404"))
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
}
