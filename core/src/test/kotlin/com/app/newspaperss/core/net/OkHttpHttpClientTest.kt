package com.app.newspaperss.core.net

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
    fun headerCharsetWins() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\u00e9".toByteArray(Charsets.UTF_8)
        assertTrue(OkHttpHttpClient.decode(bytes, Charsets.UTF_8).endsWith("\u00e9"))
    }

    @Test(expected = IOException::class)
    fun oversizedBodiesAreRefused() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray((OkHttpHttpClient.MAX_BYTES + 1).toInt()))).build())
        OkHttpHttpClient().get(server.url("/").toString())
    }

    @Test(expected = IOException::class)
    fun malformedUrlIsAnIOException() = runTest {
        OkHttpHttpClient().get("not a url")
    }
}
