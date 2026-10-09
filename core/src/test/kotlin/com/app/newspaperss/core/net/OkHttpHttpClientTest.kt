package com.app.newspaperss.core.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class OkHttpHttpClientTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test
    fun followsRedirectsAndReportsTheFinalUrl() = runTest {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved").build())
        server.enqueue(MockResponse.Builder().body("hello").addHeader("Content-Type", "text/plain").build())
        val r = OkHttpHttpClient().get(server.url("/start").toString())
        assertTrue(r.isSuccessful)
        assertEquals("hello", r.body)
        assertTrue(r.finalUrl.endsWith("/moved"))
        assertTrue(server.takeRequest().headers["User-Agent"]!!.contains("newspaperss"))
    }

    @Test
    fun errorStatusIsAResponseNotAnException() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertFalse(OkHttpHttpClient().get(server.url("/").toString()).isSuccessful)
    }

    @Test
    fun xmlPrologEncodingIsUsedWhenTheHeaderHasNoCharset() = runTest {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Caf\u00e9</title></channel></rss>"
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(xml.toByteArray(Charsets.ISO_8859_1))).addHeader("Content-Type", "text/xml").build())
        assertTrue(OkHttpHttpClient().get(server.url("/").toString()).body.contains("Caf\u00e9"))
    }

    @Test
    fun anHtmlPagesMetaCharsetIsUsedWhenTheHeaderHasNone() {
        val page = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\"></head><body>caf\u00e9 \u2014 na\u00efve</body></html>"
        val decoded = OkHttpHttpClient.decode(page.toByteArray(charset("windows-1252")), null)
        assertTrue(decoded.contains("caf\u00e9 \u2014 na\u00efve"))
        val html5 = "<!doctype html><meta charset=iso-8859-1><p>\u00e9t\u00e9</p>"
        assertTrue(OkHttpHttpClient.decode(html5.toByteArray(Charsets.ISO_8859_1), null).contains("\u00e9t\u00e9"))
    }

    @Test
    fun aPageLabelledLatin1IsReadAsWindows1252LikeBrowsersDo() {
        val page = "<meta charset=\"iso-8859-1\"><p>\u201cQuoted\u201d \u2014 caf\u00e9</p>"
        val decoded = OkHttpHttpClient.decode(page.toByteArray(charset("windows-1252")), null)
        assertTrue(decoded.contains("\u201cQuoted\u201d \u2014 caf\u00e9"))
    }

    @Test
    fun aByteOrderMarkMeansUtf8() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<meta charset=iso-8859-1>\u00e9".toByteArray(Charsets.UTF_8)
        assertTrue(OkHttpHttpClient.decode(bytes, null).endsWith("\u00e9"))
    }

    @Test
    fun headerCharsetWins() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\u00e9".toByteArray(Charsets.UTF_8)
        assertTrue(OkHttpHttpClient.decode(bytes, Charsets.UTF_8).endsWith("\u00e9"))
    }

    /** A site's whole archive in one feed, newest first: its start comes back, and the parser keeps its whole items. */
    @Test
    fun aFeedOverTheLimitIsReadUpToIt() = runTest {
        val item = "<item><title>Post</title><link>https://example.com/p</link><description>${"x".repeat(2000)}</description></item>"
        val count = (OkHttpHttpClient.MAX_BYTES / item.length + 10).toInt()
        val feed = "<rss version=\"2.0\"><channel><title>Archive</title>" + item.repeat(count) + "</channel></rss>"
        server.enqueue(MockResponse.Builder().body(feed).build())
        server.enqueue(MockResponse.Builder().body("<rss version=\"2.0\"><channel>$item</channel></rss>").build())

        val response = OkHttpHttpClient().getFeed(server.url("/feed").toString())
        assertTrue(response.truncated)
        assertEquals(OkHttpHttpClient.MAX_BYTES, response.body.length.toLong())
        val items = com.app.newspaperss.core.feed.FeedParser.parse(response.body, response.finalUrl, response.truncated).items
        assertTrue("${items.size} of $count", items.size in count - 20 until count)
        assertFalse("a feed within the limit is whole", OkHttpHttpClient().getFeed(server.url("/small").toString()).truncated)
    }

    @Test(expected = IOException::class)
    fun oversizedBodiesAreRefused() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray((OkHttpHttpClient.MAX_BYTES + 1).toInt()))).build())
        OkHttpHttpClient().get(server.url("/").toString())
    }

    @Test
    fun getBytesSendsTheGivenHeadersAndReturnsTheRawBody() = runTest {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, -1)
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(png)).addHeader("Content-Type", "image/png").build())
        val r = OkHttpHttpClient().getBytes(server.url("/a.png").toString(), mapOf("Referer" to "https://example.com/story"))
        assertTrue(r.isSuccessful)
        assertEquals("image/png", r.contentType)
        assertTrue(png.contentEquals(r.body))
        val request = server.takeRequest()
        assertEquals("https://example.com/story", request.headers["Referer"])
        assertTrue(request.headers["User-Agent"]!!.contains("newspaperss"))
    }

    @Test(expected = IOException::class)
    fun oversizedImagesAreRefused() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray((OkHttpHttpClient.MAX_IMAGE_BYTES + 1).toInt()))).build())
        OkHttpHttpClient().getBytes(server.url("/huge.jpg").toString())
    }

    @Test
    fun postJsonSendsTheBodyAsUtf8Json() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("{\"ok\":false}").build())
        val r = OkHttpHttpClient().postJson(server.url("/api/").toString(), "{\"name\":\"Caf\u00e9\"}")
        assertEquals(500, r.code)
        assertEquals("{\"ok\":false}", r.body)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("{\"name\":\"Caf\u00e9\"}", request.body?.utf8())
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertTrue(request.headers["User-Agent"]!!.contains("newspaperss"))
    }

    @Test
    fun aPostIsNeverRepeatedToWhereARedirectPoints() = runTest {
        // A 308 would make OkHttp re-send the body, credentials included, to the new address.
        server.enqueue(MockResponse.Builder().code(308).addHeader("Location", "http://elsewhere.example/api/").build())
        val r = OkHttpHttpClient().postJson(server.url("/api/").toString(), """{"op":"login","password":"secret"}""")
        assertEquals(308, r.code)
        assertEquals("http://elsewhere.example/api/", r.finalUrl)
        assertEquals(1, server.requestCount)
    }

    @Test(expected = IOException::class)
    fun malformedUrlIsAnIOException() = runTest {
        OkHttpHttpClient().get("not a url")
    }

    @Test
    fun aHeadersLatin1IsReadAsWindows1252LikeAMetaTags() {
        // Curly quotes and a dash in windows-1252 bytes, labelled Latin-1 by the server.
        val bytes = "\u201cDon\u2019t\u201d \u2014 yes".toByteArray(charset("windows-1252"))
        assertEquals("\u201cDon\u2019t\u201d \u2014 yes", OkHttpHttpClient.decode(bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun aCancelledFetchCancelsItsRequestRatherThanWaitingItOut() = runBlocking {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(30, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val fetch = launch(Dispatchers.IO) { OkHttpHttpClient().get(server.url("/slow").toString()) }
        delay(300)
        fetch.cancelAndJoin()
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
    }
}
