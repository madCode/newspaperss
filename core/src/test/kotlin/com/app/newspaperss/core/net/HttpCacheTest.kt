package com.app.newspaperss.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class HttpCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<String?>()
    private val feed = "<rss version=\"2.0\"><channel><title>Site</title></channel></rss>"

    init {
        server.createContext("/feed") { exchange ->
            val etag = "\"v1\""
            requests += exchange.requestHeaders.getFirst("If-None-Match")
            exchange.responseHeaders.add("ETag", etag)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            // Fresh for an hour, and last changed long ago: OkHttp would serve it without asking.
            exchange.responseHeaders.add("Cache-Control", "max-age=3600")
            exchange.responseHeaders.add("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")
            if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val body = feed.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        // A site's whole archive in one feed, over the size limit, cacheable like the other.
        server.createContext("/archive") { exchange ->
            exchange.responseHeaders.add("ETag", "\"a1\"")
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.responseHeaders.add("Cache-Control", "max-age=3600")
            val item = "<item><title>Post</title><link>https://example.com/p</link><description>${"x".repeat(2000)}</description></item>"
            val body = ("<rss version=\"2.0\"><channel><title>Archive</title>" + item.repeat((OkHttpHttpClient.MAX_BYTES / item.length + 50).toInt()) + "</channel></rss>").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            runCatching { exchange.responseBody.use { it.write(body) } }
            exchange.close()
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    @Test
    fun anUnchangedFeedIsRevalidatedNotDownloadedAgain() = runTest {
        val http = OkHttpHttpClient(OkHttpHttpClient.defaultClient(tmp.newFolder("http")))
        val url = "http://127.0.0.1:${server.address.port}/feed"

        val first = http.get(url)
        val second = http.get(url)

        assertEquals("asked both times, the second only whether it changed", listOf(null, "\"v1\""), requests)
        assertEquals("the cached copy stands in for the 304", feed, second.body)
        assertEquals(200, second.code)
        assertEquals(first.body, second.body)
    }

    /** Reading the start of a feed over the limit stores nothing, and leaves the other feeds' copies alone. */
    @Test
    fun aFeedOverTheLimitDoesntEmptyTheCache() = runTest {
        val cache = tmp.newFolder("http")
        val http = OkHttpHttpClient(OkHttpHttpClient.defaultClient(cache))
        val base = "http://127.0.0.1:${server.address.port}"
        http.get("$base/feed")
        assertTrue(http.getFeed("$base/archive").truncated)
        http.get("$base/feed")
        assertEquals("the small feed was revalidated from its cached copy", listOf(null, "\"v1\""), requests)
    }
}
