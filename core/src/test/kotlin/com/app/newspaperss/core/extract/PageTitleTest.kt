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

    @Test
    fun aPageWithoutATitleHasNone() {
        assertNull(PageTitle.of("<html><body><p>Just text</p></body></html>", "https://a.example/"))
    }
}
