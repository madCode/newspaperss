package com.app.newspaperss.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageExtractorTest {
    private val fixture = javaClass.getResource("/extract/article_page.html")!!.readText()
    private val url = "https://example.com/culture/slow"
    private val sentence = "The committee met again on Thursday to argue about the budget for the new library wing. "

    @Test
    fun extractsArticleWithMetadata() {
        val page = PageExtractor.extract(fixture, url)
        assertEquals("readability", page.extractor)
        assertEquals("The Quiet Joy of Reading Slowly", page.title)
        assertEquals("Jane Doe", page.author)
        assertTrue(page.wordCount > 350)
        assertTrue("particular pleasure in reading slowly" in page.html)
        assertTrue("a letter from a friend" in page.html)
        // Every article would carry the same id, which is invalid once several share a book.
        assertFalse("readability-page" in page.html)
    }

    @Test
    fun cookieBannersAndDialogsAreRemovedBeforeExtraction() {
        val modal = "<div aria-modal=\"true\"><p>${"Subscribe to our newsletter for more great content today. ".repeat(3)}</p></div>"
        val page = PageExtractor.extract(fixture.replace("</main>", "</main>$modal"), url)
        val cleaned = HtmlCleaner.clean(page.html, url, page.title).html
        assertTrue("particular pleasure" in cleaned)
        assertFalse("technical storage" in cleaned)
        assertFalse("Subscribe to our newsletter" in cleaned)
    }

    @Test
    fun overlayMarkersNeverRemoveMostOfThePage() {
        val html = "<html><body><div class=\"cookie-recipes\"><p>${sentence.repeat(20)}</p></div><p>footer</p></body></html>"
        assertTrue(PageExtractor.extract(html, "https://example.com/recipes").wordCount > 250)
    }

    @Test
    fun jsonLdArticleBodyWhenThePageHasNoText() {
        val body = "Para one.\\n\\n" + sentence.repeat(20)
        val html = """<html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"NewsArticle","articleBody":"$body"}]}
            </script></head><body><div id="app"></div><p>Subscribe to read</p></body></html>"""
        val page = PageExtractor.extract(html, "https://example.com/a")
        assertEquals("json-ld", page.extractor)
        assertTrue(page.html.startsWith("<p>Para one.</p><p>The committee"))
    }

    @Test
    fun jsonLdWinsWhenReadabilityOnlySawAPaywallPreview() {
        val preview = (1..3).joinToString("") { "<p>${sentence.repeat(4)}</p>" }
        val full = sentence.repeat(60).trim()
        val html = """<html><head><script type="application/ld+json">{"@type":"Article","articleBody":"$full"}</script></head>
            <body><article>$preview<div class="paywall-prompt">Subscribe to keep reading</div></article></body></html>"""
        val page = PageExtractor.extract(html, "https://example.com/a")
        assertEquals("json-ld", page.extractor)
        assertTrue(page.wordCount > 900)
    }

    @Test
    fun fallsBackToTheBodyWhenNothingElseWorks() {
        val page = PageExtractor.extract("<html><body><span>only this</span></body></html>", "https://example.com/a")
        assertTrue("only this" in HtmlCleaner.clean(page.html, "https://example.com/a").html)
    }

    @Test
    fun cleanTitleRemovesATrailingSiteName() {
        assertEquals("E-reader", PageExtractor.cleanTitle("E-reader - Wikipedia", url = "https://en.wikipedia.org/wiki/E-reader"))
        assertEquals("Slow reading", PageExtractor.cleanTitle("Slow reading | The Example Review", siteName = "The Example Review"))
        assertEquals("Slow reading", PageExtractor.cleanTitle("Slow reading — Jane Doe", author = "Jane Doe"))
        // A separator that isn't followed by the site's name is part of the title.
        assertEquals("Review: The Long Goodbye", PageExtractor.cleanTitle("Review: The Long Goodbye", url = "https://example.com/r"))
        assertEquals("Pros - and cons of tea", PageExtractor.cleanTitle(" Pros - and\ncons of tea ", url = "https://example.com/x"))
    }

    @Test
    fun rejectsJunkAuthors() {
        assertEquals("Jane Doe", PageExtractor.cleanAuthor("  Jane\n Doe "))
        assertNull(PageExtractor.cleanAuthor("Jane Doe is a writer who lives in Lisbon with her two cats and a large collection of plants"))
        assertNull(PageExtractor.cleanAuthor("x".repeat(81)))
        assertNull(PageExtractor.cleanAuthor(" "))
    }
}
