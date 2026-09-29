package com.app.newspaperss.core.images

import com.app.newspaperss.core.epub.EpubImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleImagesTest {
    private fun jpeg(size: Int = 10) = EncodedImage(ByteArray(size), "image/jpeg", 100, 100)

    @Test
    fun svgsAreNotDownloadedAndEachArticleHasAnImageCap() {
        val urls = listOf("https://x.com/logo.SVG?v=2", "https://x.com/chart.svg#part") +
            (1..25).map { "https://x.com/$it.jpg" }
        val wanted = ArticleImages.wanted(urls)
        assertEquals(ImageRules.MAX_PER_ARTICLE, wanted.size)
        assertEquals("https://x.com/1.jpg", wanted.first())
        assertFalse(wanted.any { ArticleImages.isSvg(it) })
        assertFalse(ArticleImages.isSvg("https://x.com/svg/photo.jpg"))
    }

    @Test
    fun imagesPointIntoTheBookInDocumentOrder() {
        val html = """<p>Intro</p><img src="https://x.com/b.jpg" alt="B"/><p>More</p><img src="https://x.com/a.jpg" alt="A"/>"""
        val result = ArticleImages.embed(html, "a7", mapOf("https://x.com/a.jpg" to jpeg(3), "https://x.com/b.jpg" to jpeg(5)))
        assertEquals(listOf("images/a7-1.jpg", "images/a7-2.jpg"), result.images.map { it.href })
        assertEquals(listOf(5, 3), result.images.map { it.bytes.size })
        assertEquals(
            """<p>Intro</p><img src="images/a7-1.jpg" alt="B" /><p>More</p><img src="images/a7-2.jpg" alt="A" />""",
            result.html,
        )
    }

    @Test
    fun theSameImageTwiceIsStoredOnce() {
        val html = """<img src="https://x.com/a.jpg"/><img src="https://x.com/a.jpg"/>"""
        val result = ArticleImages.embed(html, "a1", mapOf("https://x.com/a.jpg" to jpeg()))
        assertEquals(1, result.images.size)
        assertEquals(2, Regex("images/a1-1.jpg").findAll(result.html).count())
    }

    @Test
    fun failedImagesAndTheFiguresTheyLeaveEmptyAreRemoved() {
        val html = """<figure><img src="https://x.com/gone.jpg"/><figcaption>Lost</figcaption></figure>""" +
            """<figure><a href="https://x.com/"><img src="https://x.com/null.jpg"/></a><figcaption>Also lost</figcaption></figure>""" +
            """<figure><img src="https://x.com/ok.jpg"/><img src="https://x.com/gone.jpg"/><figcaption>Kept</figcaption></figure>""" +
            """<p>Text <img src="https://x.com/not-wanted.jpg"/> stays</p>"""
        val result = ArticleImages.embed(html, "a1", mapOf("https://x.com/ok.jpg" to jpeg(), "https://x.com/null.jpg" to null))
        assertEquals(
            """<figure><img src="images/a1-1.jpg" /><figcaption>Kept</figcaption></figure><p>Text  stays</p>""",
            result.html,
        )
        assertEquals(listOf("images/a1-1.jpg"), result.images.map { it.href })
    }

    @Test
    fun theExtensionFollowsTheMediaTypeAndUnknownTypesAreDropped() {
        val html = """<img src="https://x.com/a.png"/><img src="https://x.com/b.webp"/>"""
        val result = ArticleImages.embed(
            html, "a1",
            mapOf(
                "https://x.com/a.png" to EncodedImage(ByteArray(1), "image/png", 50, 50),
                "https://x.com/b.webp" to EncodedImage(ByteArray(1), "image/webp", 50, 50),
            ),
        )
        assertEquals(listOf("images/a1-1.png"), result.images.map { it.href })
        assertFalse(result.html.contains("webp"))
    }

    @Test
    fun articleKeysMustBeSafeInAnHref() {
        assertThrows(IllegalArgumentException::class.java) { ArticleImages.embed("<p/>", "../a", emptyMap()) }
    }

    @Test
    fun theBudgetKeepsImagesInReadingOrderAndLetsSmallerOnesFillTheGap() {
        fun img(name: String, size: Int) = EpubImage("images/$name.jpg", "image/jpeg", ByteArray(size))
        val kept = ImageBudget.fit(
            listOf(listOf(img("a1", 40), img("a2", 50)), listOf(img("b1", 20), img("b2", 10)), emptyList()),
            maxBytes = 100,
        )
        assertEquals(
            listOf(listOf("images/a1.jpg", "images/a2.jpg"), listOf("images/b2.jpg"), emptyList()),
            kept.map { a -> a.map { it.href } },
        )
        assertTrue(ImageBudget.fit(listOf(listOf(img("a", 101))), maxBytes = 100).single().isEmpty())
    }

    @Test
    fun allowanceHandsOutOnlyWhatFits() {
        val allowance = ImageAllowance(maxBytes = 100)
        assertTrue(allowance.take(60))
        assertFalse("too big for what's left", allowance.take(50))
        assertTrue(allowance.take(40))
        assertTrue(allowance.exhausted)
    }

    @Test
    fun aLeftoverTooSmallForAnImageCountsAsSpent() {
        // Otherwise every later article would download its images only to drop them.
        val nearlyFull = ImageAllowance(maxBytes = 15_000_000)
        assertTrue(nearlyFull.take(14_960_000))
        assertTrue(nearlyFull.exhausted)

        val refusing = ImageAllowance(maxBytes = 15_000_000)
        assertTrue(refusing.take(14_000_000))
        repeat(3) { assertFalse(refusing.take(2_000_000)) }
        assertTrue("three refusals in a row", refusing.exhausted)
    }
}
