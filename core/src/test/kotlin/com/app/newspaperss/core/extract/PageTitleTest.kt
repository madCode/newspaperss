package com.app.newspaperss.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTitleTest {
    @Test
    fun prefersOgTitleAndDropsTheSiteName() {
        val html = """
            <html><head><title>Home | The Example Review</title>
            <meta property="og:site_name" content="The Example Review">
            <meta property="og:title" content="Slow reading &amp; attention | The Example Review">
            </head><body><p>Text</p></body></html>
        """.trimIndent()
        assertEquals("Slow reading & attention", PageTitle.of(html, "https://review.example.com/slow"))
    }

    @Test
    fun fallsBackToTheTitleElement() {
        val html = "<html><head><meta property=\"og:title\" content=\" \"><title>\n  E-reader - Wikipedia\n</title></head></html>"
        assertEquals("E-reader", PageTitle.of(html, "https://en.wikipedia.org/wiki/E-reader"))
    }

    /** A magazine's og:title can wrap the headline in its author and issue; a subtitle or a headline cut short doesn't count. */
    @Test
    fun theHeadlineWhereTheOgTitleWrapsIt() {
        val nlr = """<html><head><meta property="og:title" content="Donald Sassoon, Changing the Guard, NLR 160, July–August 2026"></head>
            <body><article><meta itemprop="author" content="Donald Sassoon"><h1 itemprop="headline">Changing the Guard</h1></article></body></html>"""
        assertEquals("Changing the Guard", PageTitle.of(nlr, "https://review.example.com/guard"))
        val subtitle = """<html><head><meta property="og:title" content="The Long Goodbye: Britain after Brexit"><meta name="author" content="Jane Doe"></head>
            <body><h1 itemprop="headline">The Long Goodbye</h1></body></html>"""
        assertEquals("The Long Goodbye: Britain after Brexit", PageTitle.of(subtitle, "https://review.example.com/goodbye"))
        val card = """<html><head><meta property="og:title" content="Climate Change: What You Need to Know - Opinion"></head>
            <body><div itemprop="headline">Climate Change</div></body></html>"""
        assertEquals("a related card's headline", "Climate Change: What You Need to Know - Opinion", PageTitle.of(card, "https://news.example.com/c"))
        val cut = """<html><head><meta property="og:title" content="Why the library wing went over budget and what the committee plans next">
            <script type="application/ld+json">{"@type":"NewsArticle","headline":"Why the library wing went over budget and what"}</script></head></html>"""
        assertEquals("Why the library wing went over budget and what the committee plans next", PageTitle.of(cut, "https://news.example.com/wing"))
    }

    @Test
    fun aPageWithoutATitleHasNone() {
        assertNull(PageTitle.of("<html><body><p>Just text</p></body></html>", "https://a.example/"))
    }
}
