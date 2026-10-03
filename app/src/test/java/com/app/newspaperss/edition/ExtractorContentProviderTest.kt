package com.app.newspaperss.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.images.ImageAllowance
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
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
    private val evidence = mutableListOf<Pair<Long, FullTextEvidence>>()
    private val checks = mutableListOf<FullTextEvidence?>()
    private val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder()) { id, _, e, text ->
        e?.let { evidence += id to it }
        if (text.check) checks += e
    }
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

        val content = provider.contentFor(article(html), source, ImageAllowance(), TextChoice())!!

        val image = content.images.single()
        assertEquals("images/a42-1.jpg", image.href)
        assertEquals("image/jpeg", image.mediaType)
        assertTrue(content.bodyHtml.contains("<img src=\"images/a42-1.jpg\""))
        assertTrue(content.bodyHtml.contains("The photo"))
        assertFalse("a figure whose image failed goes too", content.bodyHtml.contains("Never arrives"))
        assertFalse("no img is left pointing at the web", content.bodyHtml.contains("cdn.example"))
        assertTrue(content.bodyHtml.contains("An icon"))
        assertEquals("only the origin is sent", "https://example.com/", http.bytesRequests.getValue("https://cdn.example/photo.png")["Referer"])
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

        provider.contentFor(article(html), source, ImageAllowance(), TextChoice())

        assertEquals(10, http.bytesRequests.size)
        assertEquals(4, most)
    }

    @Test
    fun noImagesAreDownloadedOnceTheEditionIsFull() = runTest {
        val html = "<p>Words.</p><p><img src=\"https://cdn.example/1.jpg\"/></p>"
        val full = ImageAllowance(maxBytes = 0)

        val content = provider.contentFor(article(html), source, full, TextChoice())!!

        assertTrue(http.bytesRequests.isEmpty())
        assertTrue(content.images.isEmpty())
    }

    private val readingList = SourceEntity(id = 2, kind = SourceKind.READING_LIST, url = "newspaperss:reading-list", title = "Saved", contentMode = ContentMode.PAGE)

    @Test
    fun aSavedLinkWithoutATitleGetsThePagesOwn() = runTest {
        val words = (1..400).joinToString(" ") { "word$it" }
        http.page("https://example.com/story", "<html><head><title>The real headline</title></head><body><article><h1>The real headline</h1><p>$words</p></article></body></html>")
        val saved = ArticleEntity(id = 7, sourceId = 2, guid = "https://example.com/story", url = "https://example.com/story", title = "")

        val content = provider.contentFor(saved, readingList, ImageAllowance(), TextChoice())!!

        assertEquals("The real headline", content.title)
    }

    @Test
    fun aSavedLinkThatCantBeFetchedWaitsInsteadOfBeingUsedUp() = runTest {
        val saved = ArticleEntity(id = 8, sourceId = 2, guid = "https://example.com/gone", url = "https://example.com/gone", title = "")
        assertEquals(null, provider.contentFor(saved, readingList, ImageAllowance(), TextChoice()))
    }

    @Test
    fun eachArticleReportsWhatItShowedAboutItsSource() = runTest {
        val auto = source.copy(contentMode = ContentMode.AUTO)
        val words = (1..600).joinToString(" ") { "word$it" }
        http.page("https://example.com/story", "<html><body><article><p>$words</p></article></body></html>")
        http.page("https://example.com/blocked", "<html>Forbidden</html>", code = 403)
        http.unreachable += "https://example.com/offline"

        provider.contentFor(article("<p>A short teaser.</p>"), auto, ImageAllowance(), TextChoice())
        provider.contentFor(article("<p>A short teaser.</p>").copy(url = "https://example.com/blocked"), auto, ImageAllowance(), TextChoice())
        provider.contentFor(article("<p>A short teaser.</p>").copy(url = "https://example.com/offline"), auto, ImageAllowance(), TextChoice())

        assertEquals(
            "an unreachable page could just be the phone being offline, so it isn't evidence",
            listOf(1L to FullTextEvidence.PAGE_LONGER, 1L to FullTextEvidence.BLOCKED),
            evidence,
        )
    }

    /**
     * A site settled on the feed's text because it was blocking can stop blocking: its short
     * items keep being checked against the page so the source can switch back. A reader's own
     * choice of the feed's text is left alone.
     */
    @Test
    fun aSourceSettledOnTheFeedStillHasShortItemsChecked() = runTest {
        val words = (1..600).joinToString(" ") { "word$it" }
        http.page("https://example.com/story", "<html><body><article><p>$words</p></article></body></html>")

        val settled = provider.contentFor(article("<p>A short teaser.</p>"), source, ImageAllowance(), TextChoice())
        val chosen = provider.contentFor(article("<p>A short teaser.</p>"), source, ImageAllowance(), TextChoice(chosen = ContentMode.FEED))

        assertEquals(listOf(1L to FullTextEvidence.PAGE_LONGER), evidence)
        assertEquals(600, settled?.wordCount)
        assertEquals(3, chosen?.wordCount)
    }

    private fun words(n: Int, word: String) = (1..n).joinToString(" ") { "$word$it" }

    private fun pageWith(text: String) = http.page("https://example.com/story", "<html><body><article><p>$text</p></article></body></html>")

    /** A long item is taken from the feed without looking at its page, so only a check can tell a long teaser from a whole article. */
    @Test
    fun aCheckTellsALongTeaserFromAWholeArticle() = runTest {
        val auto = source.copy(contentMode = ContentMode.AUTO)
        val whole = "<p>${words(600, "feed")}</p>"

        pageWith(words(620, "page"))
        val unchecked = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice())!!
        val confirmed = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice(check = true))!!
        pageWith(words(1500, "page"))
        val teaser = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice(check = true))!!

        assertEquals("taken from the feed, so it says nothing", listOf(FullTextEvidence.FEED_FULL, FullTextEvidence.PAGE_LONGER), checks)
        assertEquals(checks, evidence.map { it.second })
        assertTrue(unchecked.bodyHtml.contains("feed1 "))
        assertTrue("a whole article keeps the feed's text it would have had", confirmed.bodyHtml.contains("feed1 "))
        assertEquals("a teaser is replaced by its page in this edition already", 1500, teaser.wordCount)
    }

    /** A check is extra: whatever happens to the page, a whole article comes out as it would have without one. */
    @Test
    fun aCheckThatCantReadThePageLeavesTheArticleAlone() = runTest {
        val auto = source.copy(contentMode = ContentMode.AUTO)
        val whole = "<p>${words(600, "feed")}</p>"
        val plain = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice())!!

        http.page("https://example.com/story", "<html>Forbidden</html>", code = 403)
        val blocked = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice(check = true))!!
        http.page("https://example.com/story", "", code = 404)
        val gone = provider.contentFor(article(whole), auto, ImageAllowance(), TextChoice(check = true))!!

        assertEquals(plain.bodyHtml, blocked.bodyHtml)
        assertEquals("no note about a page the reader never asked for", null, blocked.note)
        assertEquals(null, gone.note)
        assertEquals("both count as the day's check, so it isn't retried every edition", 2, checks.size)
        assertEquals(listOf(FullTextEvidence.FEED_FULL, null), checks)
    }

    @Test
    fun aShorterPageWithAPictureDoesntReplaceAWholeArticle() = runTest {
        val auto = source.copy(contentMode = ContentMode.AUTO)
        http.files["https://example.com/hero.png"] = "image/png" to transparentPng(600, 400)
        http.page("https://example.com/story", "<html><body><article><img src=\"https://example.com/hero.png\"/><p>${words(450, "page")}</p></article></body></html>")

        val content = provider.contentFor(article("<p>${words(600, "feed")}</p>"), auto, ImageAllowance(), TextChoice(check = true))!!

        assertTrue(content.bodyHtml.contains("feed1 "))
        assertEquals(listOf<FullTextEvidence?>(FullTextEvidence.FEED_FULL), checks)
    }

    @Test
    fun aPublicationThatLearnedThePageHasItsLongItemsFetched() = runTest {
        val auto = source.copy(contentMode = ContentMode.AUTO)
        pageWith(words(1500, "page"))
        val content = provider.contentFor(article("<p>${words(600, "feed")}</p>"), auto, ImageAllowance(), TextChoice(learned = ContentMode.PAGE))!!
        assertEquals(1500, content.wordCount)
    }
}
