package com.app.newspaperss.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
            if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val body = feed.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
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

        assertEquals(listOf(null, "\"v1\""), requests)
        assertEquals("the cached copy stands in for the 304", feed, second.body)
        assertEquals(200, second.code)
        assertEquals(first.body, second.body)
    }
}
