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

    @Test(expected = IOException::class)
    fun malformedUrlIsAnIOException() = runTest {
        OkHttpHttpClient().get("not a url")
    }
}
