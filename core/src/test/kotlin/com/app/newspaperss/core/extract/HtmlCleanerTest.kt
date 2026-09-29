package com.app.newspaperss.core.extract

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCleanerTest {
    private val base = "https://example.com/articles/one"
    private fun clean(html: String, title: String? = null) = HtmlCleaner.clean(html, base, title).html
    private val longText = "word ".repeat(100)

    @Test
    fun plainTextBecomesParagraphs() {
        assertEquals("<p>first para still first</p><p>second &lt;3</p>", clean("first para\nstill first\n\nsecond <3"))
        assertEquals(CleanResult("", 0, emptyList()), HtmlCleaner.clean("   ", base, null))
    }

    @Test
    fun removesScriptsStylesAndPageFurniture() {
        val html = """<div><script>alert(1)</script><style>p{}</style><nav>Home</nav>
            <p>Real text that is long enough to matter in this article.</p>
            <div class="share-tools">Share on Facebook</div><div id="newsletter-signup">Sign up!</div>
            <aside>Sidebar</aside><form><input/></form><iframe src="x"></iframe><button>Follow</button>
            <svg><text>chart</text></svg><p style="display: none">hidden</p><p hidden>also hidden</p><!-- a comment --></div>"""
        assertEquals("<div>\n<p>Real text that is long enough to matter in this article.</p>\n</div>", clean(html))
    }

    @Test
    fun junkClassNeverRemovesMostOfTheArticle() {
        assertTrue("word" in clean("<div class=\"comment-body\"><p>$longText</p></div><p>byline</p>"))
    }

    @Test
    fun keepsParagraphsInPaywallContainers() {
        val sections = (0 until 4).joinToString("") { "<div class=\"body__inner-container paywall\"><p>Section $it ${"text ".repeat(30)}</p></div>" }
        val cleaned = clean("<div>$sections</div>")
        (0 until 4).forEach { assertTrue("Section $it" in cleaned) }
    }

    @Test
    fun keepsArticleBodyMarkedAriaHiddenButDropsSmallHiddenBits() {
        val cleaned = clean("<section aria-hidden=\"true\"><p>$longText</p><span aria-hidden=\"true\">icon</span></section>")
        assertTrue("word" in cleaned)
        assertFalse("icon" in cleaned)
    }

    @Test
    fun stripsAttributesAndUnwrapsUnknownTags() {
        assertEquals(
            "<p>Hi <b>there</b></p>",
            clean("<p class=\"x\" style=\"color:red\" onclick=\"evil()\"><span><font>Hi</font></span> <b data-x=\"1\">there</b></p>"),
        )
        // Unknown containers go but their text stays.
        assertEquals("<p>Kept</p>", clean("<article><section><main><p>Kept</p></main></section></article>"))
    }

    @Test
    fun resolvesAndFiltersLinks() {
        assertEquals(
            "<p><a href=\"https://example.com/other\">rel</a> js <a href=\"https://a.com/b\">abs</a> none " +
                "<a href=\"mailto:ed@example.com\">mail</a> <a href=\"https://cdn.example.net/x\">proto</a></p>",
            clean(
                "<p><a href=\"/other\">rel</a> <a href=\"javascript:x()\">js</a> <a href=\"https://a.com/b\">abs</a> <a>none</a> " +
                    "<a href=\"mailto:ed@example.com\">mail</a> <a href=\"//cdn.example.net/x\">proto</a></p>",
            ),
        )
    }

    @Test
    fun resolvesAgainstABaseWithoutAPath() {
        assertEquals(
            "<p><a href=\"https://example.com/x\">x</a></p>",
            HtmlCleaner.clean("<p><a href=\"x\">x</a></p>", "https://example.com", null).html,
        )
    }

    @Test
    fun encodesCharactersUrlsCannotContain() {
        val cleaned = clean("<p><a href=\"https://a.com/x y#one#two\">a</a> <a href=\"/p?q={1}|2\">b</a> <a href=\"/café\">c</a></p>")
        assertEquals(
            "<p><a href=\"https://a.com/x%20y#one%23two\">a</a> <a href=\"https://example.com/p?q=%7B1%7D%7C2\">b</a> " +
                "<a href=\"https://example.com/caf%C3%A9\">c</a></p>",
            cleaned,
        )
        assertEquals("<p><a href=\"https://a.com/a%20b\">x</a></p>", clean("<p><a href=\"https://a.com/a%20b\">x</a></p>"))
        assertEquals("<p>bad</p>", clean("<p><a href='\\\"http:/x\\\"'>bad</a></p>"))
    }

    @Test
    fun fragmentLinksPointAtValidUniqueIds() {
        assertEquals(
            "<p><a href=\"#fn1\">1</a> 2</p><p id=\"fn1\">Note</p><p id=\"id-1-x\">a</p><p>b</p>",
            clean("<p><a href=\"#fn1\">1</a> <a href=\"#missing\">2</a></p><p id=\"fn1\">Note</p><p id=\"1 x\">a</p><p id=\"1 x\">b</p>"),
        )
    }

    @Test
    fun fixesLazyImagesAndDropsTrackingPixels() {
        val result = HtmlCleaner.clean(
            "<img src=\"data:image/gif;base64,R0lGODlhAQABAAAAACw=\" data-src=\"/lazy.jpg\" alt=\" A \">" +
                "<img src=\"/plain.jpg\" width=\"600\">" +
                "<img src=\"/pixel.gif\" width=\"1\" height=\"1\">" +
                "<img src=\"https://t.example.net/p.gif\" height=\"2px\">" +
                "<img data-lazy-src=\"/lazy2.jpg\">" +
                "<img data-srcset=\"/s.jpg 300w, /m.jpg 1024w, /l.jpg 2000w\">" +
                "<img alt=\"no source\">",
            base, null,
        )
        assertEquals(
            "<img src=\"https://example.com/lazy.jpg\" alt=\"A\" /><img src=\"https://example.com/plain.jpg\" alt=\"\" />" +
                "<img src=\"https://example.com/lazy2.jpg\" alt=\"\" /><img src=\"https://example.com/m.jpg\" alt=\"\" />",
            result.html,
        )
        assertEquals(
            listOf("https://example.com/lazy.jpg", "https://example.com/plain.jpg", "https://example.com/lazy2.jpg", "https://example.com/m.jpg"),
            result.imageUrls,
        )
    }

    @Test
    fun captionsOutsideTheEdgeOfAFigureBecomeDivs() {
        // Nested in a layout div inside the figure (seen on Quanta Magazine).
        assertEquals(
            "<figure><img src=\"https://example.com/a.jpg\" alt=\"\" /><div><div><div><p>Photo: A. Person</p></div></div></div></figure>",
            clean("<figure><img src=\"/a.jpg\"><div><figcaption><div><p>Photo: A. Person</p></div></figcaption></div></figure>"),
        )
        assertEquals("<div>Loose caption</div><p>Text.</p>", clean("<figcaption>Loose caption</figcaption><p>Text.</p>"))
        assertEquals(
            "<figure><div>One</div><img src=\"https://example.com/a.jpg\" alt=\"\" /><figcaption>Two</figcaption></figure>",
            clean("<figure><figcaption>One</figcaption><img src=\"/a.jpg\"><figcaption>Two</figcaption></figure>"),
        )
        // A credit span is unwrapped to bare text, which leaves the caption short of the figure's end.
        assertEquals(
            "<figure><img src=\"https://example.com/a.jpg\" alt=\"\" /><div>Cap</div>Photo: Getty</figure>",
            clean("<figure><img src=\"/a.jpg\"><figcaption>Cap</figcaption><span>Photo: Getty</span></figure>"),
        )
    }

    @Test
    fun aRelatedLinksListGoesButARelatedSectionOfTheArticleStays() {
        val body = "<p>" + "Words of the article itself. ".repeat(30) + "</p>"
        assertEquals(
            body,
            clean("$body<h2>Read next</h2><ul><li><a href=\"/a\">Story A</a></li><li><a href=\"/b\">Story B</a></li></ul>"),
        )
        val ownSection = "<h2>Related research</h2><p>Earlier studies found the same effect in mice.</p>"
        assertEquals(body + ownSection, clean(body + ownSection))
        val gallery = "<h2>More from our photographers</h2><ul><li><a href=\"https://example.com/p\"><img src=\"https://example.com/p.jpg\" alt=\"\" /></a></li></ul>"
        assertEquals("a list of pictures isn't a list of links", body + gallery, clean(body + gallery))
    }

    /** A long reading list that is the article, as in a newsletter item or an essay's references, stays. */
    @Test
    fun aLongListUnderARelatedHeadingIsKept() {
        val items = (1..12).joinToString("") { "<li><a href=\"https://example.com/$it\">A long and interesting book title number $it</a></li>" }
        val html = "<p>A short introduction.</p><h2>Further reading</h2><ul>$items</ul>"
        assertEquals(12, Jsoup.parse(clean(html)).select("li").size)
    }


    @Test
    fun pictureElementsPreferJpegSources() {
        val html = "<figure><picture><source srcset=\"/a.webp\" type=\"image/webp\">" +
            "<source srcset=\"/a.jpg 1x, /a@2x.jpg 2x\" type=\"image/jpeg\"><img alt=\"pic\"></picture><figcaption>Cap</figcaption></figure>"
        assertEquals("<figure><img src=\"https://example.com/a@2x.jpg\" alt=\"pic\" /><figcaption>Cap</figcaption></figure>", clean(html))
        // The picture's own <img> wins when it has a source.
        assertEquals(
            "<img src=\"https://example.com/own.jpg\" alt=\"\" />",
            clean("<picture><source srcset=\"/other.jpg\"><img src=\"/own.jpg\"></picture>"),
        )
    }

    @Test
    fun srcsetPicksAnImageBigEnoughButNotHuge() {
        assertEquals("b.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg 300w, b.jpg 1200w, c.jpg 2400w"))
        assertEquals("b.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg 300w, b.jpg 600w"))
        assertEquals("b.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg 1x, b.jpg 2x, c.jpg 3x"))
        assertEquals("a.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg"))
        // Image CDNs put commas inside URLs.
        assertEquals(
            "https://img.example.com/w_1200,q_80/x.jpg",
            HtmlCleaner.bestSrcsetCandidate("https://img.example.com/w_400,q_80/x.jpg 400w, https://img.example.com/w_1200,q_80/x.jpg 1200w"),
        )
        assertNull(HtmlCleaner.bestSrcsetCandidate(""))
    }

    @Test
    fun removesLeadingTitleAndDemotesHeadings() {
        assertEquals("<p>Intro</p><h2>Section</h2><h4>Sub</h4>", clean("<h1>The Title!</h1><p>Intro</p><h1>Section</h1><h3>Sub</h3>", "The title"))
        assertEquals("<p>Intro</p><h2>The Title</h2>", clean("<p>Intro</p><h2>The Title</h2>", "The title"))
        // Headings already starting at h2 stay as they are.
        assertEquals("<h2>A</h2><h3>B</h3>", clean("<h2>A</h2><h3>B</h3>"))
    }

    @Test
    fun removesBoilerplateAndEmptyElements() {
        val html = "<p>Listen to this article</p><p>5 min read</p><p></p><div><span> </span></div><p><br></p>" +
            "<p>Keep me</p><p>Advertisement</p><figure><img src=\"/pixel.gif\" width=\"1\"></figure>"
        assertEquals("<p>Keep me</p>", clean(html))
        // A long paragraph that merely starts like boilerplate is article text.
        val sentence = "Sign up for the draft was compulsory for every man aged eighteen to twenty five in the country."
        assertEquals("<p>$sentence</p>", clean("<p>$sentence</p>"))
    }

    @Test
    fun flattensLayoutTablesButKeepsDataTables() {
        val data = "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"
        assertEquals("<table><tbody><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></tbody></table>", clean(data))
        val nested = "<table><tr><td><table><tr><td>x</td><td>y</td></tr></table></td></tr></table>"
        assertFalse("<table" in clean(nested))
        val singleColumn = "<table><tr><td><p>Newsletter paragraph</p></td></tr><tr><td><p>Another</p></td></tr></table>"
        assertEquals("<div><div><div><div><p>Newsletter paragraph</p></div></div><div><div><p>Another</p></div></div></div></div>", clean(singleColumn))
        val presentation = "<table role=\"presentation\"><tr><td>left</td><td>right</td></tr></table>"
        assertFalse("<table" in clean(presentation))
    }

    @Test
    fun countsWordsOfTheCleanedText() {
        val result = HtmlCleaner.clean("<p>One two three.</p><script>var a = b c d;</script><div class=\"share\">Share</div><p>Four</p>", base, null)
        assertEquals(4, result.wordCount)
    }

    @Test
    fun outputIsXhtml() {
        assertEquals("<p>a<br />b &amp; c&#xa0;d</p><hr />", clean("<p>a<br>b &amp; c&nbsp;d</p><hr>"))
    }
}
