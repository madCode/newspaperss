package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ArticleExtractorTest {
    private class FakeHttp(private val responses: Map<String, HttpResponse>) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse {
            requested += url
            return responses[url] ?: throw IOException("no route to host")
        }
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
    }

    private val url = "https://example.com/culture/slow"
    private val fixture = javaClass.getResource("/extract/article_page.html")!!.readText()
    private val sentence = "The committee met again on Thursday to argue about the budget for the new library wing. "
    private val teaser = "<p>There is a particular pleasure in reading slowly. <a href=\"/culture/slow\">Read more</a></p>"

    private fun page(body: String = fixture, code: Int = 200, finalUrl: String = url, type: String? = "text/html; charset=utf-8") =
        HttpResponse(code, finalUrl, type, body)

    private fun input(feedHtml: String?, mode: ContentMode = ContentMode.AUTO, feedTitle: String = "The Quiet Joy of Reading Slowly") =
        ExtractInput(url, feedTitle, feedHtml, feedAuthor = null, mode = mode)

    @Test
    fun autoFetchesThePageWhenTheFeedIsATeaser() = runTest {
        val http = FakeHttp(mapOf(url to page()))
        val article = ArticleExtractor(http).extract(input(teaser))
        assertEquals(listOf(url), http.requested)
        assertFalse(article.usedFeedContent)
        assertNull(article.note)
        assertEquals("Jane Doe", article.author)
        assertEquals(listOf("https://example.com/images/hero.jpg"), article.imageUrls)
        assertTrue("a letter from a friend" in article.html)
        // Page furniture and the repeated title don't reach the reader.
        listOf("Comments (212)", "Share on Facebook", "Sign up for our newsletter", "technical storage", "<h1>", "Quiet Joy of")
            .forEach { assertFalse("\"$it\" leaked", it in article.html) }
        assertEquals(article.wordCount / 238.0, article.minutes, 1e-9)
        assertEquals(ContentMode.PAGE, ArticleExtractor.suggestMode(article.feedWordCount, article.pageWordCount))
    }

    @Test
    fun autoUsesFullTextFeedContentWithoutFetching() = runTest {
        val http = FakeHttp(emptyMap())
        val feed = "<p>${sentence.repeat(30)}</p><img src=\"/chart.png\">"
        val article = ArticleExtractor(http).extract(input(feed).copy(feedAuthor = "Sam Lee"))
        assertTrue(http.requested.isEmpty())
        assertTrue(article.usedFeedContent)
        assertEquals("Sam Lee", article.author)
        assertEquals(listOf("https://example.com/chart.png"), article.imageUrls)
        assertTrue(article.wordCount >= ArticleExtractor.FULL_TEXT_WORDS)
    }

    @Test
    fun feedModeNeverFetchesAndPageModeAlwaysDoes() = runTest {
        val http = FakeHttp(mapOf(url to page()))
        val fromFeed = ArticleExtractor(http).extract(input(teaser, ContentMode.FEED))
        assertTrue(http.requested.isEmpty())
        assertTrue(fromFeed.usedFeedContent)

        val fullFeed = "<p>${sentence.repeat(30)}</p>"
        val fromPage = ArticleExtractor(http).extract(input(fullFeed, ContentMode.PAGE))
        assertEquals(listOf(url), http.requested)
        assertFalse(fromPage.usedFeedContent)
    }

    @Test
    fun feedModeItemWithoutContentFetchesThePage() = runTest {
        val http = FakeHttp(mapOf(url to page()))
        val article = ArticleExtractor(http).extract(input(null, ContentMode.FEED))
        assertFalse(article.usedFeedContent)
        assertTrue("particular pleasure" in article.html)
    }

    @Test
    fun keepsTheFeedWhenExtractionFindsMuchLess() = runTest {
        val thinPage = "<html><body><article><p>${sentence.repeat(3)}</p></article></body></html>"
        val feed = "<p>${sentence.repeat(15)}</p>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(thinPage)))).extract(input(feed))
        assertTrue(article.usedFeedContent)
        assertNull(article.note)
        assertEquals(ContentMode.FEED, ArticleExtractor.suggestMode(article.feedWordCount, article.pageWordCount))
    }

    @Test
    fun fetchFailuresFallBackToTheFeedWithANote() = runTest {
        val cases = mapOf(
            "network" to FakeHttp(emptyMap()),
            "404" to FakeHttp(mapOf(url to page("<html><body>Not found</body></html>", code = 404))),
            "blocked" to FakeHttp(mapOf(url to page("<html>Forbidden</html>", code = 403))),
            "challenge" to FakeHttp(mapOf(url to page("<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>"))),
            "pdf" to FakeHttp(mapOf(url to page("%PDF-1.7 binary", type = "application/pdf"))),
        )
        for ((name, http) in cases) {
            val article = ArticleExtractor(http).extract(input(teaser))
            assertTrue(name, article.usedFeedContent)
            assertTrue(name, article.note!!.startsWith("Couldn't fetch the full article"))
            assertTrue(name, "particular pleasure" in article.html)
            assertFalse(name, "Checking your browser" in article.html)
        }
    }

    @Test
    fun failureWithoutFeedContentStillProducesAnArticleLinkingThePage() = runTest {
        val http = FakeHttp(mapOf(url to page("<html>Forbidden</html>", code = 403)))
        val article = ArticleExtractor(http).extract(input(null, feedTitle = ""))
        assertEquals("Slow (example.com)", article.title)
        assertEquals("Couldn't fetch this article.", article.note)
        assertTrue("error 403" in article.html)
        assertTrue("<a href=\"$url\">" in article.html)
        assertEquals(0, article.wordCount)
    }

    @Test
    fun pageTitleIsUsedWhenTheItemHasNone() = runTest {
        val article = ArticleExtractor(FakeHttp(mapOf(url to page()))).extract(input(null, feedTitle = " "))
        assertEquals("The Quiet Joy of Reading Slowly", article.title)
    }

    @Test
    fun linksResolveAgainstTheUrlAfterRedirects() = runTest {
        val moved = "https://www.example.org/2025/03/slow"
        val body = fixture.replace("<p>Part of the reason", "<p><a href=\"notes\">Notes</a>. Part of the reason")
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(body, finalUrl = moved)))).extract(input(null))
        assertTrue("href=\"https://www.example.org/2025/03/notes\"" in article.html)
        assertEquals(listOf("https://www.example.org/images/hero.jpg"), article.imageUrls)
    }

    @Test
    fun suggestModeNeedsBothCounts() {
        assertNull(ArticleExtractor.suggestMode(100, null))
        assertNull(ArticleExtractor.suggestMode(0, 500))
        assertNull(ArticleExtractor.suggestMode(100, 150))
    }

    @Test
    fun titleFromUrl() {
        assertEquals("Why genre matters (example.com)", ArticleExtractor.titleFromUrl("https://www.example.com/2026/09/why-genre-matters/123"))
        assertEquals("Story (example.com)", ArticleExtractor.titleFromUrl("https://example.com/story.html"))
        assertEquals("example.com", ArticleExtractor.titleFromUrl("https://example.com/"))
    }
}
