package com.app.newspaperss.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.transparentPng
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtractorContentProviderTest {
    private val http = FakeHttp()
    private val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder())
    private val source = SourceEntity(id = 1, url = "https://example.com/feed", title = "Example", contentMode = ContentMode.FEED)

    private fun article(feedHtml: String) = ArticleEntity(
        id = 42, sourceId = 1, guid = "g", url = "https://example.com/story", title = "A story", feedHtml = feedHtml,
    )

    @Test
    fun usableImagesAreEmbeddedAndTheRestLeaveNoTrace() = runTest {
        http.files["https://cdn.example/photo.png"] = "image/png" to transparentPng(600, 400)
        http.files["https://cdn.example/icon.png"] = "image/png" to transparentPng(32, 32)
        http.files["https://cdn.example/chart"] = "image/svg+xml" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        val html = """
            <p>Words about the thing that happened today.</p>
            <figure><img src="https://cdn.example/photo.png" alt="Photo"/><figcaption>The photo</figcaption></figure>
            <figure><img src="https://cdn.example/missing.jpg"/><figcaption>Never arrives</figcaption></figure>
            <p>An icon <img src="https://cdn.example/icon.png" alt="icon"/> inline.</p>
            <p><img src="https://cdn.example/diagram.svg" alt="diagram"/><img src="https://cdn.example/chart" alt="chart"/></p>
        """.trimIndent()

        val content = provider.contentFor(article(html), source)

        val image = content.images.single()
        assertEquals("images/a42-1.jpg", image.href)
        assertEquals("image/jpeg", image.mediaType)
        assertTrue(content.bodyHtml.contains("<img src=\"images/a42-1.jpg\""))
        assertTrue(content.bodyHtml.contains("The photo"))
        assertFalse("a figure whose image failed goes too", content.bodyHtml.contains("Never arrives"))
        assertFalse("no img is left pointing at the web", content.bodyHtml.contains("cdn.example"))
        assertTrue(content.bodyHtml.contains("An icon"))
        assertEquals("https://example.com/story", http.bytesRequests.getValue("https://cdn.example/photo.png")["Referer"])
        assertFalse("SVG URLs aren't downloaded", "https://cdn.example/diagram.svg" in http.bytesRequests)
    }

    @Test
    fun atMostFourImagesDownloadAtOnce() = runTest {
        var inFlight = 0
        var most = 0
        http.beforeResponse = { url ->
            if (url.startsWith("https://cdn.example/")) {
                most = maxOf(most, ++inFlight)
                delay(100)
                inFlight--
            }
        }
        val html = "<p>Words.</p>" + (1..10).joinToString("") { "<p><img src=\"https://cdn.example/$it.jpg\"/></p>" }

        provider.contentFor(article(html), source)

        assertEquals(10, http.bytesRequests.size)
        assertEquals(4, most)
    }
}
