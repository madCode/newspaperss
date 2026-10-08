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

    /** A Substack Note is filled in by the page's script; without it the sentence introducing it would hang. */
    @Test
    fun aSubstackNoteBecomesAQuoteWithItsAuthor() {
        val attrs = """{"url":"https://open.substack.com/","comment":{"id":1,"body":"Quixote vomits in Sancho's face \"more vigorously than if he were firing a musket.\"","name":"Lincoln Michel"}}"""
            .replace("\"", "&quot;")
        val note = """<p>gross-out comedy you might expect from <em>South Park</em>:</p>"""
        // As the feed has it, and as tt-rss passes it on, with the class stripped.
        for (embed in listOf("""<div class="comment" data-attrs="$attrs"></div>""", """<div data-attrs="$attrs"></div>""")) {
            val html = clean("$note$embed<p>$longText</p>")
            assertTrue(html, html.contains("<blockquote><p>Quixote vomits in Sancho's face \"more vigorously than if he were firing a musket.\"</p><p>— Lincoln Michel</p></blockquote>"))
        }
        assertFalse("other data-attrs are left alone", clean("""<div data-attrs="{&quot;src&quot;:&quot;x&quot;}"></div><p>$longText</p>""").contains("blockquote"))
        assertFalse("a hidden one stays hidden", clean("""<div hidden data-attrs="$attrs"></div><p>$longText</p>""").contains("blockquote"))
        val second = attrs.replace("Lincoln Michel", "Someone Else")
        val inline = clean("""<p>As I wrote: <span data-attrs="$attrs"></span> and <span data-attrs="$second"></span></p><p>$longText</p>""")
        assertTrue("a quote can't sit inside a paragraph", inline.contains("</p><blockquote>"))
        assertTrue("several keep their order", inline.indexOf("Lincoln Michel") < inline.indexOf("Someone Else"))
    }

    /** Substack's feed has an @-mention as an empty span its page script fills in from `data-attrs`. */
    @Test
    fun aSubstackMentionKeepsItsName() {
        val attrs = """{&quot;name&quot;:&quot;jane doe&quot;,&quot;id&quot;:1,&quot;type&quot;:&quot;user&quot;,&quot;url&quot;:null}"""
        // As the feed has it, and with tt-rss's stripping of classes.
        for (mention in listOf(
            """<span class="mention-wrap" data-attrs="$attrs" data-component-name="MentionToDOM"></span>""",
            """<span data-attrs="$attrs"></span>""",
        )) {
            val html = clean("<p>As $mention points out, she had to get a tablet.</p><p>$longText</p>")
            assertTrue(html, html.contains("<p>As jane doe points out, she had to get a tablet.</p>"))
        }
        // The page itself has the script's result: the name isn't doubled.
        val page = clean("""<p>As <span data-attrs="$attrs"><a href="https://substack.com/@jane">jane doe</a></span> points out.</p><p>$longText</p>""")
        assertEquals(1, Regex("jane doe").findAll(page).count())
    }

    /** tt-rss strips ids and resolves "#footnote-4" against the site; the book's footnotes must still work. */
    @Test
    fun footnotesSurviveTtrssStrippingTheirIdsAndResolvingTheirLinks() {
        val html = clean(
            """<p>Like Shakespeare.<a href="https://example.com#footnote-4">4</a> Quite true.</p>
               <p>$longText</p>
               <div><a href="https://example.com#footnote-anchor-4">4</a><div><p>The note itself.</p></div></div>""",
        )
        val doc = Jsoup.parse(html)
        val forward = doc.select("a[href=#footnote-4]").single()
        assertEquals("footnote-anchor-4", forward.id())
        val back = doc.select("a[href=#footnote-anchor-4]").single()
        assertEquals("footnote-4", back.id())
    }

    @Test
    fun aLinkToThisPagePlusAFragmentBecomesAnInBookLink() {
        val html = clean("""<p id="part-two">Part two.</p><p><a href="$base#part-two">back to part two</a> $longText</p>""")
        assertTrue(html, html.contains("""<a href="#part-two">back to part two</a>"""))
        val tracked = HtmlCleaner.clean("""<p id="part-two">Part two.</p><p><a href="$base#part-two">back</a> $longText</p>""", "$base?utm_source=rss", null).html
        assertTrue("a tracking query on the page's address doesn't matter", tracked.contains("""<a href="#part-two">back</a>"""))
    }

    @Test
    fun aFootnoteIsntPairedWithAnUnrelatedNumberedLink() {
        // A numbered contents entry beside a footnote whose back-link is long: no pair to make.
        val html = clean(
            """<p><a href="https://example.com#part-1">1</a> Part one. Text.<a href="https://example.com#fn-1">1</a></p>
               <p>$longText</p><p id="part-1">Part one</p><p>The note. <a href="https://example.com#fnref-1">Back to the text</a></p>""",
        )
        val doc = Jsoup.parse(html)
        assertEquals("the contents entry still finds its part", "#part-1", doc.select("a").first()!!.attr("href"))
        assertTrue("and isn't given the footnote's id", doc.select("#fn-1").isEmpty())
    }

    @Test
    fun aSiteUnderAPathCountsAsTheArticlesOwn() {
        val html = HtmlCleaner.clean(
            """<p>Text.<a href="http://blog.example.com/notes/#footnote-1">1</a></p><p>$longText</p>
               <div><a href="http://blog.example.com/notes/#footnote-anchor-1">1</a> The note.</div>""",
            "https://www.blog.example.com/notes/2026/a-post", null,
        ).html
        assertTrue(html, html.contains("""href="#footnote-1""""))
        assertTrue(html, html.contains("""id="footnote-1""""))
    }

    /** With nothing in the book to land on, a link to the site's front page stays one. */
    @Test
    fun aLinkToTheSitesFrontPageWithAFragmentStaysALink() {
        val html = clean("""<p><a href="https://example.com/#subscribe">Subscribe to the newsletter</a> $longText</p>""")
        assertTrue(html, html.contains("""href="https://example.com/#subscribe""""))
    }

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

    @Test
    fun anAuthorsOwnListsAndSectionTitlesStay() {
        val body = "<p>" + "Words of the article itself. ".repeat(30) + "</p>"
        val kept = listOf(
            "<h2>Further reading</h2><ul><li><a href=\"https://example.com/1\">A paper</a></li><li><a href=\"https://example.com/2\">A book</a></li></ul>",
            "<h2>Read more</h2>Two earlier pieces on this:<ul><li><a href=\"https://example.com/1\">One</a></li></ul>",
            "<h2>More on the method</h2><p>We sampled weekly.</p>",
            "<h2>Read more</h2><p>Our findings are below.</p>",
            "<h2>Recommended</h2><ul><li>Plain advice</li><li>Without links</li></ul>",
        )
        for (section in kept) assertEquals(section, body + section, clean(body + section))
    }

    /** An extractor can drop a box's links but keep its heading, over the article's next paragraph. */
    @Test
    fun aFurnitureHeadingLeftWithoutItsLinksGoesAndABoxGoesWhole() {
        val body = "<p>" + "Words of the article itself. ".repeat(30) + "</p>"
        assertEquals(body + body, clean("$body<h2>Recommended Stories</h2>$body"))
        val links = "<ul><li><a href=\"https://example.com/a\">Story A</a></li><li><a href=\"https://example.com/b\">Story B</a></li></ul>"
        assertEquals(body, clean("$body<div class=\"more\"><h3>Related stories</h3>$links$links</div>"))
        // Feed HTML has line breaks between tags.
        assertEquals(body, clean("$body\n<h2>Read next</h2>\n$links\n"))
    }

    @Test
    fun screenReaderTextThatLabelsAnIconLinkStays() {
        assertEquals(
            "<p>Text <a href=\"https://example.com/f.pdf\">Download PDF</a> end.</p>",
            clean("<p>Text <a href=\"https://example.com/f.pdf\"><span class=\"sr-only\">Download PDF</span></a> end.</p>"),
        )
        assertEquals(
            "<p><a href=\"https://example.com/m\">Open menu</a></p>",
            clean("<p><a href=\"https://example.com/m\"><span class=\"sr-only\">Open</span> <span class=\"sr-only\">menu</span></a></p>"),
        )
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

    /** tt-rss joins the candidates it passes on with bare commas; a CDN's commas inside URLs mustn't split them. */
    @Test
    fun srcsetJoinedWithBareCommasStillOffersEveryCandidate() {
        assertEquals(
            "https://img.example.com/w_1200,q_80/x.jpg",
            HtmlCleaner.bestSrcsetCandidate("https://img.example.com/w_400,q_80/x.jpg 400w,https://img.example.com/w_1200,q_80/x.jpg 1200w"),
        )
        assertEquals("b.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg 1x,b.jpg 2x"))
        assertEquals("b.jpg", HtmlCleaner.bestSrcsetCandidate("a.jpg 1.5x,b.jpg 2x"))
    }

    /** A video can't play in a book; its poster can show, so its caption still has something to describe. */
    @Test
    fun aVideoBecomesItsPosterAndAPlayerALink() {
        val video = clean("""<figure><video src="/v.mp4" poster="/v.jpg"></video><figcaption>The robot prison</figcaption></figure><p>$longText</p>""")
        assertTrue(video, video.contains("""<figure><img src="https://example.com/v.jpg" alt="Video" /><figcaption>The robot prison</figcaption></figure>"""))
        val player = clean("""<iframe src="https://www.youtube.com/embed/6PkAr_RKzlY?feature=oembed" title="How I grew a newsletter"></iframe><p>$longText</p>""")
        assertTrue(player, player.contains("""<a href="https://www.youtube.com/watch?v=6PkAr_RKzlY"><img src="https://i.ytimg.com/vi/6PkAr_RKzlY/hqdefault.jpg" alt="How I grew a newsletter" /></a>"""))
        assertTrue(player, player.contains("""<p><a href="https://www.youtube.com/watch?v=6PkAr_RKzlY">Watch on YouTube: How I grew a newsletter</a></p>"""))
        assertTrue(clean("""<iframe src="https://player.vimeo.com/video/123456"></iframe><p>$longText</p>""").contains("""<a href="https://vimeo.com/123456">Watch on Vimeo</a>"""))
        assertFalse("other players still go", clean("""<iframe src="https://ads.example.com/x"></iframe><p>$longText</p>""").contains("iframe"))
    }

    /** tt-rss passes no iframes on: the caption left behind becomes a paragraph rather than captioning nothing. */
    @Test
    fun aCaptionWhoseVideoIsGoneBecomesAParagraph() {
        assertEquals("<p>The robot prison</p><p>$longText</p>".trim(), clean("""<figure><figcaption>The robot prison</figcaption></figure><p>$longText</p>""").trim())
        assertEquals("<p>Two</p><p>lines</p>", clean("""<figure><figcaption><p>Two</p><p>lines</p></figcaption></figure>"""))
    }

    /** Substack ends a paid post's opening in its feed with "Read more" back to the post; the book links to it anyway. */
    @Test
    fun aClosingReadMoreToThePostItselfGoes() {
        val result = HtmlCleaner.clean("""<p>$longText</p><p> <a href="$base"> Read more </a> </p>""", base, null)
        assertTrue(result.teaser)
        assertFalse(result.html, result.html.contains("Read more"))
        val elsewhere = HtmlCleaner.clean("""<p>$longText</p><p><a href="https://other.example.com/story">Read more</a></p>""", base, null)
        assertFalse("a link to another page is the author's", elsewhere.teaser)
        assertTrue(elsewhere.html.contains("Read more"))
        val midway = HtmlCleaner.clean("""<p><a href="$base">Read more</a></p><p>$longText</p>""", base, null)
        assertFalse("only at the very end", midway.teaser)
    }

    @Test
    fun newsletterPitchesGoButAnAuthorsOwnLineStays() {
        for (pitch in listOf(
            "Countercraft is a reader-supported publication. To receive new posts and support my work, consider becoming a free or paid subscriber.",
            "Subscribe to Slow Boring to keep reading this post and get 7 days of free access to the full post archives.",
            "Don't miss what's next. Subscribe to Computer Things:",
            "<em>The Marginalian</em> has a free weekly newsletter. It comes out on Sundays. Like? <a href=\"/newsletter/\">Sign up.</a>",
        )) assertFalse(pitch, clean("<p>$pitch</p><p>$longText</p>").contains("ubscri") || clean("<p>$pitch</p><p>$longText</p>").contains("Sign up"))
        assertEquals("", clean("<h5>Add a comment:</h5><h3>newsletter</h3>"))
        for (own in listOf(
            "If you're reading this on the web, you can subscribe here. Updates are once a week.",
            "Three newsletters I subscribe to and love:",
            "As the banner put it, \"subscribe to The Atlantic to keep reading\", which I did not.",
        )) assertTrue(own, clean("<p>$own</p><p>$longText</p>").contains(own.replace("\"", "&quot;")) || clean("<p>$own</p><p>$longText</p>").contains(own))
        val micro = "Just launched my newsletter, go <a href=\"https://example.com/n\">sign up</a>."
        assertTrue("a sign-up link mid-sentence is the author's", clean("<p>$micro</p>").contains("launched my newsletter"))
        val shortPost = "<div><p>Worth reading: a fine essay on slowness.</p><p>Thanks for reading Foo! Subscribe for free to receive new posts and support my work.</p></div>"
        assertTrue("a wrapper holding a pitch isn't one", clean(shortPost).contains("Worth reading"))
        assertFalse(clean(shortPost).contains("Subscribe"))
    }

    @Test
    fun removesLeadingTitleAndDemotesHeadings() {
        assertEquals("<p>Intro</p><h2>Section</h2><h4>Sub</h4>", clean("<h1>The Title!</h1><p>Intro</p><h1>Section</h1><h3>Sub</h3>", "The title"))
        assertEquals("<p>Intro</p><h2>The Title</h2>", clean("<p>Intro</p><h2>The Title</h2>", "The title"))
        // A feed's title can name the author first; a one-word heading could be the first section.
        assertEquals("<p>Intro</p>", clean("<h2>Changing the Guard</h2><p>Intro</p>", "Donald Sassoon: Changing the Guard"))
        assertEquals("<h2>Introduction</h2><p>Intro</p>", clean("<h2>Introduction</h2><p>Intro</p>", "Jane Doe: Introduction"))
        // Headings already starting at h2 stay as they are.
        assertEquals("<h2>A</h2><h3>B</h3>", clean("<h2>A</h2><h3>B</h3>"))
    }

    /** A magazine's notes sit in a <footer> after the text; a page footer nothing links into still goes. */
    @Test
    fun footnotesInAFooterStay() {
        val html = clean(
            """<p>$longText<a href="#note-1" id="ref-1"><sup>1</sup></a></p>""" +
                """<footer><div id="note-1"><a href="#ref-1">1</a> Ibid., p. 4.</div></footer>""" +
                """<footer><p>© Example Press. <a href="#top">Back to top</a></p></footer>""",
        )
        assertTrue(html, "Ibid., p. 4." in html)
        assertTrue(html, """href="#note-1"""" in html)
        assertFalse(html, "Example Press" in html)
        // A note with paragraphs of its own stays a div: a paragraph can't hold one.
        val long = clean("""<p>$longText<a href="#fn1">1</a></p><footer><div id="fn1"><section><p>One.</p><p>Two.</p></section></div></footer>""")
        assertFalse(long, Regex("<p[^>]*>[^<]*<p").containsMatchIn(long))
    }

    /** Small capitals are written in lower case and styled; "us" set as small capitals is the US. */
    @Test
    fun smallCapitalsAreWrittenOutInCapitals() {
        assertEquals("<p>Tory MPs and the US left.</p>", clean("""<p>Tory <span class="small-caps">mp</span>s and the <span style="font-variant: small-caps">us</span> left.</p>"""))
        assertEquals("<div><p>İSTANBUL</p></div>", clean("""<div lang="tr"><p><span class="smallcaps">istanbul</span></p></div>"""))
    }

    /** Foundation's class for screen-reader text, here labelling a footnote marker. */
    @Test
    fun foundationScreenReaderTextGoes() {
        assertEquals("<p>Text.<sup>1</sup></p>", clean("""<p>Text.<a href="#gone"><span class="show-for-sr">footnote</span><sup>1</sup></a></p>"""))
    }

    /** Substack's buttons, as the feed has them and as tt-rss passes them on without their classes. */
    @Test
    fun substackShareAndGiftButtonsGo() {
        val post = "https://www.example.com/p/a-post"
        for (button in listOf(
            """<p class="button-wrapper"><a class="button primary" href="$post?utm_source=substack&amp;utm_content=share&amp;action=share"><span>Share</span></a></p>""",
            """<p><a href="https://www.example.com/subscribe?&amp;gift=true">Give a gift subscription</a></p>""",
            """<p><a href="$post/comments">Leave a comment</a></p>""",
        )) {
            assertEquals(button, "<p>$longText</p>".trim(), clean("<p>$longText</p>$button").trim())
        }
        for (own in listOf(
            """<p><a href="https://www.example.com/p/another-post">Another post</a></p>""",
            """<p><a href="https://other.example.com/p/their-post/comments">A lively thread on their post</a></p>""",
        )) assertTrue("a link of the author's own stays", "post" in clean("<p>$longText</p>$own"))
        val chart = """<div><a href="https://www.example.com/subscribe"><img src="https://www.example.com/chart.png"></a></div>"""
        assertTrue("an image that links somewhere stays", "chart.png" in clean("<p>$longText</p>$chart"))
    }

    /** Substack's card for another post keeps its title and summary but not its button or likes. */
    @Test
    fun anEmbeddedPostLosesItsButtonAndLikes() {
        val card = """<div><a href="https://other.example.com/p/x"><div><img src="https://cdn.example.com/logo.png">Other Letter</div>""" +
            """<div><div>The next chapter</div></div><div>Read more</div><div>4 years ago · 71 likes · 32 comments · A Writer</div></a></div>"""
        val html = clean("<p>$longText</p>$card")
        assertTrue(html, "The next chapter" in html)
        assertFalse(html, "Read more" in html)
        assertFalse(html, "likes" in html)
    }

    /** MemberPress's pitch in a feed passed on by tt-rss, which strips the classes that name it. */
    @Test
    fun aMembershipPitchGoesWithItsBox() {
        val pitch = """<div><hr><p>This premium article is available to paid subscribers of the newsletter. Here's what subscribers get:</p>""" +
            """<ul><li>Weekly news</li><li>The archive</li></ul><h2><a href="https://example.com/join">Subscribe now.</a></h2></div>""" +
            """<div><div><a href="https://example.com/login/?action=forgot_password">Forgot Password</a></div></div>"""
        assertEquals("<div><p>The free part.</p></div>", clean("<div><p>The free part.</p></div>$pitch"))
        // A short post that opens with a pitch is still a post.
        val post = "<div><p>Consider becoming a paid subscriber to support my work.</p>" + "<p>${"A paragraph of the post itself, about walking. ".repeat(3)}</p>".repeat(4) + "</div>"
        assertTrue(clean(post), "about walking" in clean(post))
    }

    /** Substack's site footer, which a paid post's short free part lets into Readability's pick. */
    @Test
    fun aSitesFooterGoes() {
        val footer = """<div class="footer-wrap publication-footer"><div class="footer-terms"><a href="https://substack.com/privacy">Privacy</a> ∙ """ +
            """<a href="https://substack.com/tos">Terms</a></div><div><a href="https://substack.com/signup">Start your Substack</a>""" +
            """<a href="https://substack.com/app/app-store-redirect">Get the app</a></div></div>"""
        assertEquals("<p>$longText</p>".trim(), clean("<p>$longText</p>$footer").trim())
    }

    /** A magazine's line asking for sign-ups, at the end of an article or in a box within it. */
    @Test
    fun aNewsletterPitchGoes() {
        for (pitch in listOf(
            """<p><em>Enjoying</em> <a href="https://n.example/">Nautilus</a><em>? Subscribe to our free</em> <a href="https://n.example/newsletter/">newsletter</a>.</p>""",
            """<div><div>🌘</div><div><a href="https://m.example/signup/">Subscribe</a> to Example Media to get The Abstract, our newsletter about science news.</div></div>""",
        )) {
            assertEquals(pitch, "<p>$longText</p>".trim(), clean("<p>$longText</p>$pitch").trim())
        }
        val aboutNewsletters = "<p>I subscribe to a newsletter about birds, and it changed how I walk.</p>"
        assertTrue("an author's own sentence about a newsletter stays", "birds" in clean("<p>$longText</p>$aboutNewsletters"))
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

    private val xkcd = "<img src=\"https://imgs.xkcd.com/comics/sandwich.png\" title=\"Proper User Policy apparently means Simon Says.\" alt=\"Sandwich\" />"

    /** A webcomic's hover text is often its second joke, and e-readers have no hover. */
    @Test
    fun anImagesTitleTextBecomesItsCaption() {
        val result = HtmlCleaner.clean(xkcd, base)
        assertEquals(
            "<figure><img src=\"https://imgs.xkcd.com/comics/sandwich.png\" alt=\"Sandwich\" />" +
                "<figcaption>Proper User Policy apparently means Simon Says.</figcaption></figure>",
            result.html,
        )
        // Still a picture with no words, so the rules for comics treat it as one.
        assertEquals(0, result.wordCount)
        // Linked, and alone in a paragraph, as feeds often wrap a comic: the figure takes their place.
        val linked = clean("<p><a href=\"https://xkcd.com/149/\">$xkcd</a></p>")
        assertEquals("<figure><p><a href=\"https://xkcd.com/149/\"><img", linked.substringBefore(" src="))
        assertTrue(linked, linked.endsWith("</a></p><figcaption>Proper User Policy apparently means Simon Says.</figcaption></figure>"))
        // A figure of its own gets the caption, unless it has one.
        assertTrue(clean("<figure>$xkcd</figure>").endsWith("<figcaption>Proper User Policy apparently means Simon Says.</figcaption></figure>"))
        assertEquals(1, Jsoup.parse(clean("<figure>$xkcd<figcaption>By Randall</figcaption></figure>")).select("figcaption").size)
    }

    @Test
    fun aTitleThatAddsNothingIsNoCaption() {
        val titles = listOf("", "  ", "Sandwich", "sandwich.png", "sandwich", "IMG 1234")
        for (title in titles) {
            val src = if (title == "IMG 1234") "https://example.com/IMG_1234.jpg" else "https://example.com/sandwich.png"
            val html = clean("<img src=\"$src\" title=\"$title\" alt=\"Sandwich\"><p>$longText</p>")
            assertFalse("\"$title\": $html", html.contains("figcaption"))
        }
        // Already on the page under the image: not said twice.
        val shown = clean("$xkcd<p>Proper User Policy apparently means Simon Says.</p>")
        assertFalse(shown, shown.contains("figcaption"))
        // An image within a line of text can't take a figure there; the text is left as it is.
        val inline = clean("<p>Here it is $xkcd in the middle.</p>")
        assertFalse(inline, inline.contains("figure"))
        for (line in listOf(
            "<ul><li><img src=\"https://example.com/pdf.png\" title=\"PDF document\"> Download the report</li></ul>",
            "<div>Text <a href=\"https://example.com/x\"><img src=\"https://example.com/i.png\" title=\"A picture\"></a> more</div>",
            "Before $xkcd after",
        )) {
            val html = clean("$line<p>$longText</p>")
            assertFalse(html, html.contains("figure"))
        }
    }

    @Test
    fun panelsSharingOneTitleAreCaptionedOnce() {
        // As PageExtractor gives a comic's panels, each in its own figure.
        val panels = (1..3).joinToString("") { "<figure><img src=\"https://example.com/strip-$it.png\" title=\"One joke for the strip\" alt=\"\"></figure>" }
        val html = clean("<div>$panels</div>")
        assertEquals(html, 1, Jsoup.parse(html).select("figcaption").size)
        assertEquals(3, Jsoup.parse(html).select("img").size)
    }

    @Test
    fun outputIsXhtml() {
        assertEquals("<p>a<br />b &amp; c&#xa0;d</p><hr />", clean("<p>a<br>b &amp; c&nbsp;d</p><hr>"))
    }

    @Test
    fun aHostWithAnUnderscoreIsASiteButAnAuthorityWithNoHostIsnt() {
        assertEquals("https://my_site.example/x", HtmlCleaner.absoluteUrl("https://my_site.example/x", ""))
        assertEquals(null, HtmlCleaner.absoluteUrl("http://user@/x", ""))
        assertEquals("https://xn--bcher-kva.example/x", HtmlCleaner.absoluteUrl("https://bücher.example/x", ""))
    }
}
