package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
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
        override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("not used")
    }

    private val url = "https://example.com/culture/slow"
    private val fixture = javaClass.getResource("/extract/article_page.html")!!.readText()
    private val sentence = "The committee met again on Thursday to argue about the budget for the new library wing. "
    private val teaser = "<p>There is a particular pleasure in reading slowly. <a href=\"/culture/slow\">Read more</a></p>"

    private fun page(body: String = fixture, code: Int = 200, finalUrl: String = url, type: String? = "text/html; charset=utf-8") =
        HttpResponse(code, finalUrl, type, body)

    private fun input(feedHtml: String?, mode: ContentMode = ContentMode.AUTO, feedTitle: String = "The Quiet Joy of Reading Slowly") =
        ExtractInput(url, feedTitle, feedHtml, feedAuthor = null, mode = mode)

    /** Shaped like sive.rs: a short post's own address redirects to the page holding all of them. */
    @Test
    fun aPostRedirectedToThePageHoldingAllPostsIsItsFeedText() = runTest {
        val all = "https://example.com/d"
        val short = "When walking a city, what do different experts notice that you don't? Fun book on this."
        val allPage = page("<html><body><div><p>${sentence.repeat(40)}</p></div><div><p>$short</p></div></body></html>", finalUrl = all)
        val http = FakeHttp(mapOf("$all/1290" to allPage, "https://example.com/blog/essay" to page(finalUrl = "https://example.com/blog")))
        val article = ArticleExtractor(http).extract(ExtractInput("$all/1290", "A post", "<p>$short</p>", null))
        assertTrue(article.usedFeedContent)
        assertFalse(article.html.contains("committee"))
        // A post sent up to a page that doesn't have it (a deleted post, to the blog's front page) isn't one.
        assertFalse(ArticleExtractor(http).extract(ExtractInput("https://example.com/blog/essay", "", "<p>$short</p>", null)).usedFeedContent)
    }

    /** A blog's day page holds several posts; an untitled one linking to its spot there is only its own text. */
    @Test
    fun aPostLinkingToItsSpotOnADayPageIsItsFeedText() = runTest {
        val day = "https://example.com/2026/10/05.html"
        val short = "What's the best way to block a browser from a website? Is there a meta code for that?"
        val dayPage = page("<html><body><div><a name=\"a1\"></a><p>${sentence.repeat(40)}</p></div>" +
            "<div><a name=\"a2\"></a><p>$short</p></div></body></html>", finalUrl = day)
        val http = FakeHttp(listOf("$day#a2", "$day#ref=rss", "$day#a1").associateWith { dayPage })
        for (mode in listOf(ContentMode.AUTO, ContentMode.PAGE)) {
            val article = ArticleExtractor(http).extract(ExtractInput("$day#a2", "", "<p>$short</p>", null, mode = mode))
            assertTrue(mode.name, article.usedFeedContent)
            assertFalse(mode.name, article.html.contains("committee"))
            assertNull("it says nothing about the source", FullTextCheck.evidence(article))
        }
        // A tracking tag names nothing on the page.
        assertFalse(ArticleExtractor(http).extract(ExtractInput("$day#ref=rss", "", "<p>$short</p>", null)).usedFeedContent)
        // An excerpt ending in "Read more" is never the whole entry.
        val excerpt = "<p>$short <a href=\"$day#a2\">Read more</a></p>"
        assertFalse(ArticleExtractor(http).extract(ExtractInput("$day#a2", "", excerpt, null)).usedFeedContent)
        // A reading list's teaser isn't on the page it points into: the page is the article.
        assertFalse(ArticleExtractor(http).extract(ExtractInput("$day#a1", "", "<p>An essay on libraries, and budgets.</p>", null)).usedFeedContent)
    }

    @Test
    fun readingThePageCountsThePicturesInEachVersion() = runTest {
        val http = FakeHttp(mapOf(url to page()))
        val article = ArticleExtractor(http).extract(input(teaser))
        assertEquals("the teaser has none", 0, article.feedImageCount)
        assertEquals("the page's article has its hero image", 1, article.pageImageCount)
    }

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

    private val footer = "<footer><p>© 2026 Example Media. All rights reserved. About us, careers, contact, press, accessibility help, " +
        "user agreement, privacy policy, your privacy rights, cookie settings, site map, newsletters, subscribe, " +
        "gift subscriptions, customer care, digital edition, crossword, archive and media kit.</p></footer>"

    @Test
    fun aComicWhoseFeedItemIsJustTheImageKeepsIt() = runTest {
        val comic = "<img src=\"https://example.com/comics/moons.png\" alt=\"A joke about moons\">"
        val http = FakeHttp(mapOf(url to page("<html><body><div id=\"comic\"><p>Comic</p></div>$footer</body></html>")))

        val article = ArticleExtractor(http).extract(input(comic))

        assertTrue(article.usedFeedContent)
        assertEquals(listOf("https://example.com/comics/moons.png"), article.imageUrls)
        assertFalse("All rights reserved" in article.html)
    }

    @Test
    fun aWebcomicsOwnComicBeatsTheFeedsThumbnail() = runTest {
        // ComicControl feeds carry a thumbnail; the page has the comic in img#cc-comic.
        val feedItem = "<a href=\"$url\"><img src=\"https://example.com/comicsthumbs/1-page.png\" /><br />New comic!</a><p>Edith has ideas.</p>"
        val page = "<html><body><div id=\"cc-comicbody\"><img title=\"The building\" src=\"https://example.com/comics/1-page.png\" id=\"cc-comic\"/></div>" +
            "$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(feedItem))

        assertEquals(listOf("https://example.com/comics/1-page.png"), article.imageUrls)
        assertTrue("the feed's words are the caption", "Edith has ideas" in article.html)
    }

    @Test
    fun xkcdsHoverTextGoesUnderTheComicOnce() = runTest {
        val hover = "Proper User Policy apparently means Simon Says."
        val comic = "<img src=\"https://imgs.xkcd.com/comics/sandwich.png\" title=\"$hover\" alt=\"Sandwich\" />"
        val page = "<html><body><div id=\"ctitle\">Sandwich</div><div id=\"comic\">$comic</div>$footer</body></html>"

        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(comic, feedTitle = "Sandwich"))

        assertEquals(listOf("https://imgs.xkcd.com/comics/sandwich.png"), article.imageUrls)
        val captions = Jsoup.parse(article.html).select("figure > figcaption")
        assertEquals(listOf(hover), captions.map { it.text() })
    }

    @Test
    fun hoverTextOnBothTheThumbnailAndTheComicGoesUnderTheComic() = runTest {
        val hover = "The building was never finished."
        val feedItem = "<p><img src=\"https://example.com/comicsthumbs/1-page.png\" title=\"$hover\" /></p><p>New comic!</p>"
        val page = "<html><body><div id=\"cc-comicbody\"><img title=\"$hover\" src=\"https://example.com/comics/1-page.png\" id=\"cc-comic\"/></div>" +
            "$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(feedItem))

        assertEquals(listOf("https://example.com/comics/1-page.png"), article.imageUrls)
        val figures = Jsoup.parse(article.html).select("figure")
        assertEquals(article.html, 1, figures.size)
        assertEquals("https://example.com/comics/1-page.png", figures.single().selectFirst("img")!!.attr("src"))
        assertEquals(hover, figures.single().selectFirst("figcaption")!!.text())
        assertEquals("said once", 1, Regex(Regex.escape(hover)).findAll(article.html).count())
        assertTrue("the feed's own words stay", "New comic!" in article.html)
    }

    @Test
    fun aComicsCaptionAndEveryPanelSurvive() = runTest {
        val feedItem = "<figure><img src=\"https://example.com/thumbs/strip.png\"><figcaption>The joke, told in the caption.</figcaption></figure>"
        val page = "<html><body><div id=\"comic\"><img src=\"https://example.com/strips/panel-1.png\"><img src=\"https://example.com/strips/panel-2.png\"></div>" +
            "$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(feedItem))

        assertEquals(listOf("https://example.com/strips/panel-1.png", "https://example.com/strips/panel-2.png"), article.imageUrls)
        assertTrue("told in the caption" in article.html)
    }

    @Test
    fun aCartoonPageGivesItsImageNotItsFooter() = runTest {
        val cartoon = "<html><head><meta property=\"og:description\" content=\"A drawing about the news.\"></head><body>" +
            "<header><img alt=\"Example\" class=\"logo\" src=\"/logo.png\"></header><article><h1>Daily Cartoon</h1>" +
            "<img class=\"byline-avatar\" src=\"https://cdn.example.com/people/jo.jpg\"><picture>" +
            "<img alt=\"Two fans in the stands.\" loading=\"lazy\" srcset=\"https://cdn.example.com/c/w_600/a.jpg 600w, " +
            "https://cdn.example.com/c/w_1200/a.jpg 1200w\"></picture></article>$footer</body></html>"
        val http = FakeHttp(mapOf(url to page(cartoon)))

        val article = ArticleExtractor(http).extract(input("<p>A drawing that riffs on the news.</p>"))

        assertEquals(listOf("https://cdn.example.com/c/w_1200/a.jpg"), article.imageUrls)
        assertTrue("the feed's line is the caption", "riffs on the news" in article.html)
        assertFalse("footer text isn't the caption", "All rights reserved" in article.html)
    }

    private val brief = "<p>The council voted on Tuesday, after a long and heated debate, to keep the library open " +
        "on Sundays. The decision, which surprised many, takes effect next month, and staff say they are ready.</p>"

    @Test
    fun aShortBriefDoesntGetTheSitesShareCard() = runTest {
        val page = "<html><head><meta property=\"og:image\" content=\"https://example.com/share-card.png\"></head><body>" +
            "<article><h1>Library stays open</h1>$brief$brief</article>$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(teaser))

        assertEquals(emptyList<String>(), article.imageUrls)
        assertTrue("library open" in article.html)
    }

    @Test
    fun aOneLineTeaserWithAThumbnailDoesntBeatTheShortPage() = runTest {
        val thumbTeaser = "<p>The council met again this week to talk about the library.</p><img src=\"https://example.com/thumbs/library.jpg\">"
        val page = "<html><body><div class=\"story\"><h1>Library stays open</h1>$brief$brief$brief</div>$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(thumbTeaser))

        assertFalse(article.usedFeedContent)
        assertTrue("library open" in article.html)
    }

    @Test
    fun relatedStoryCardsDontLendTheirThumbnails() = runTest {
        val card = "<article class=\"card\"><img src=\"https://example.com/thumbs/other.jpg\" width=\"600\"><h3>Another story entirely</h3></article>"
        val page = "<html><body><div class=\"story\"><h1>Library stays open</h1>$brief$brief</div><section>$card$card</section>$footer</body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(teaser))

        assertEquals(emptyList<String>(), article.imageUrls)
        assertTrue("library open" in article.html)
    }

    @Test
    fun aPageWithNoTextStillSaysItCouldntBeRead() = runTest {
        val page = "<html><body><article><img src=\"https://example.com/big.jpg\" width=\"1200\"></article></body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(null))

        assertEquals(emptyList<String>(), article.imageUrls)
        assertTrue(article.html, "example.com/culture/slow" in article.html)
    }

    @Test
    fun anArticleWithTextAndNoImageIsLeftAlone() = runTest {
        val paragraph = "<p>The committee met again on Thursday, as it has every week since spring, to argue about the budget, " +
            "the architects and, above all, the new library wing. Nobody expected a decision, and none came.</p>"
        val http = FakeHttp(mapOf(url to page("<html><body><article>${paragraph.repeat(6)}</article>$footer</body></html>")))

        val article = ArticleExtractor(http).extract(input(teaser))

        assertEquals(emptyList<String>(), article.imageUrls)
        assertTrue("library wing" in article.html)
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
            "paywall" to FakeHttp(mapOf(url to page("<html><head><title>Accès restreint</title></head></html>", code = 402))),
            "challenge" to FakeHttp(mapOf(url to page("<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>"))),
            "pdf" to FakeHttp(mapOf(url to page("%PDF-1.7 binary", type = "application/pdf"))),
            "fastly" to FakeHttp(mapOf(url to page(
                "<html><head><link href=\"/_fs-ch-1T1w/assets/styles.css\" rel=\"stylesheet\"/><title>Client Challenge</title></head>" +
                    "<body><div id=\"loading-error\">Checking your browser. A required part of this site couldn't load.</div></body></html>",
            ))),
        )
        for ((name, http) in cases) {
            val article = ArticleExtractor(http).extract(input(teaser))
            assertTrue(name, article.usedFeedContent)
            assertTrue(name, article.note!!.startsWith("Couldn't fetch the full article"))
            assertTrue(name, "particular pleasure" in article.html)
            assertFalse(name, "Checking your browser" in article.html)
            assertEquals("only a refusal or bot check counts as blocked: $name", name in setOf("blocked", "paywall", "challenge", "fastly"), article.pageBlocked)
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
        assertTrue(article.pageBlocked)
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

    private val portuguese = "A câmara municipal votou na terça-feira para ampliar as ciclovias ao longo do rio, mas vários membros " +
        "disseram que o plano foi apressado e que os moradores não foram consultados. "

    @Test
    fun anArticleKnowsItsLanguageFromItsTextAndKeepsThePagesMorePreciseTag() = runTest {
        val page = "<html lang=\"pt-BR\"><body><article><h1>Ciclovias</h1>" + "<p>$portuguese</p>".repeat(8) + "</article></body></html>"
        val fromPage = ArticleExtractor(FakeHttp(mapOf(url to page(page)))).extract(input(teaser, feedTitle = "Ciclovias"))
        assertEquals("pt-BR", fromPage.language)

        // A feed's text has no declaration to go on.
        val fromFeed = ArticleExtractor(FakeHttp(emptyMap())).extract(input("<p>${portuguese.repeat(4)}</p>", mode = ContentMode.FEED, feedTitle = "Ciclovias"))
        assertEquals("pt", fromFeed.language)
    }

    @Test
    fun aHugePageIsntParsedAndTheFeedsTextIsUsed() = runTest {
        val huge = "<html><body><article>" + "<p>${sentence.repeat(20)}</p>".repeat(3_500) + "</article></body></html>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(huge)))).extract(input(teaser))

        assertTrue(article.usedFeedContent)
        assertTrue(article.note!!, "too large" in article.note!!)
    }

    @Test
    fun aPageThatCantBeReadDoesntTeachTheSourceToStopFetchingPages() = runTest {
        // A source the check set to pages, with long teasers: a page too big to parse (every day,
        // the same) mustn't read as "the feed is enough", or the source would be stuck on teasers.
        val huge = "<html><body><article>" + "<p>${sentence.repeat(20)}</p>".repeat(3_500) + "</article></body></html>"
        val longTeaser = "<p>${sentence.repeat(20)}</p>"
        val article = ArticleExtractor(FakeHttp(mapOf(url to page(huge)))).extract(input(longTeaser, mode = ContentMode.PAGE))

        assertTrue(article.usedFeedContent)
        assertTrue(article.feedWordCount >= ArticleExtractor.FULL_TEXT_WORDS)
        assertEquals(PageFailure.PERMANENT, article.pageFailure)
        assertNull(FullTextCheck.evidence(article))
    }

    @Test
    fun aLongJapaneseFeedItemIsTheFullArticleNotATeaser() = runTest {
        // Japanese has no spaces: counted by them, 1,500 characters would be "a few words", a
        // teaser, and the page would be fetched even though the feed has the whole article.
        val japanese = "<p>" + "市議会は火曜日、川沿いの自転車専用レーンを延長することを決めた。".repeat(50) + "</p>"
        val http = FakeHttp(emptyMap())
        val article = ArticleExtractor(http).extract(input(japanese, feedTitle = "自転車専用レーン"))

        assertTrue(article.usedFeedContent)
        assertTrue(http.requested.isEmpty())
        assertTrue("${article.minutes}", article.minutes > 3)
    }

    private fun words(n: Int, prefix: String) = (1..n).joinToString(" ") { "$prefix$it" }

    private fun storyPage(words: Int, title: String = "Dusklands", author: String? = "Ann Writer") = page(
        "<html><head><title>$title</title><meta property=\"og:site_name\" content=\"Equator\">" +
            (author?.let { "<meta name=\"author\" content=\"$it\">" } ?: "") +
            "</head><body><article><h1>$title</h1><p>${words(words, "story")}</p></article></body></html>",
    )

    private val pitch = "<p>${words(200, "pitch")} <a href=\"/2026/09/about\">About</a></p>"

    private fun linkPost(title: String = "Dusklands") =
        ExtractInput(url, title, pitch, feedAuthor = "Pick Editor", mode = ContentMode.PAGE, feedUrl = "https://picks.example/2026/09/dusklands/")

    @Test
    fun aLinkPostsStoryReplacesItsPitchAndIsBylinedByTheStorysAuthor() = runTest {
        val story = ArticleExtractor(FakeHttp(mapOf(url to storyPage(3000)))).extract(linkPost())
        assertFalse(story.usedFeedContent)
        assertTrue("story3000" in story.html)
        assertEquals("Equator", story.siteName)
        assertEquals("the pick's editor wrote the pitch, not the story", "Ann Writer", story.author)

        val unsigned = ArticleExtractor(FakeHttp(mapOf(url to storyPage(3000, author = null)))).extract(linkPost())
        assertNull(unsigned.author)
    }

    /** A page not much longer than the pitch is a paywall preview: the pitch stays, saying why, still credited to the story's site. */
    @Test
    fun aLinkPostWhoseStoryPageIsShortKeepsItsPitchWithANote() = runTest {
        val preview = ArticleExtractor(FakeHttp(mapOf(url to storyPage(300)))).extract(linkPost())
        assertTrue(preview.usedFeedContent)
        assertEquals(ArticleExtractor.SHORT_STORY_NOTE, preview.note)
        assertEquals("Equator", preview.siteName)
        assertFalse(preview.notTheStory)
        assertTrue("the pitch's own links resolve against its page", "https://picks.example/2026/09/about" in preview.html)

        val ordinary = ArticleExtractor(FakeHttp(mapOf(url to storyPage(300)))).extract(linkPost().copy(feedUrl = null))
        assertFalse("an ordinary teaser gives way to the same page", ordinary.usedFeedContent)
    }

    /** A short commentary post whose one tagged link is to a page about something else stays the post. */
    @Test
    fun aLinkPostWhosePageIsTitledForSomethingElseStaysItself() = runTest {
        val http = FakeHttp(mapOf(url to storyPage(3000, title = "Quarterly earnings beat forecasts at Example Corp")))
        val post = ArticleExtractor(http).extract(linkPost(title = "Why I finally deleted my social media accounts"))
        assertTrue(post.usedFeedContent)
        assertTrue(post.notTheStory)
        assertNull(post.note)
        assertNull(post.siteName)
    }

    private val substackPaywall = """<div data-testid="paywall"><h2>Keep reading with a 7-day free trial</h2></div>"""

    private fun paywalledPage(words: Int, marker: String = substackPaywall) =
        page("<html><head><title>The Quiet Joy of Reading Slowly</title></head><body><article><h1>The Quiet Joy of Reading Slowly</h1>" +
            "<p>${sentence.repeat(words / 16 + 1)}</p></article>$marker</body></html>")

    /** A paid post's free part says so, and with next to nothing free it's flagged for the source's skip setting. */
    @Test
    fun aPaywalledPageIsThePaidPostsFreePart() = runTest {
        val preview = ArticleExtractor(FakeHttp(mapOf(url to paywalledPage(200)))).extract(input(teaser))
        assertTrue(preview.paidPost)
        assertEquals(ArticleExtractor.PAID_NOTE, preview.note)
        assertFalse(preview.nothingFree)
        val ghost = """<aside class="gh-post-upgrade-cta"><h2>This post is for paying subscribers only</h2></aside>"""
        val bare = ArticleExtractor(FakeHttp(mapOf(url to paywalledPage(10, ghost)))).extract(input("<p>A sentence of preview.</p>"))
        assertTrue(bare.nothingFree)
    }

    /** Metered sites mark their pages as not free and serve the whole story; a subscriber's own feed is whole too. */
    @Test
    fun aWholeArticleIsntCalledPaidWhateverThePageSays() = runTest {
        val metered = paywalledPage(400, """<script type="application/ld+json">{"@type":"NewsArticle","isAccessibleForFree":false}</script>""")
        assertFalse(ArticleExtractor(FakeHttp(mapOf(url to metered))).extract(input(teaser)).paidPost)
        val whole = "<p>${sentence.repeat(30)}</p>"
        assertFalse(ArticleExtractor(FakeHttp(mapOf(url to paywalledPage(200)))).extract(input(whole, ContentMode.PAGE)).paidPost)
    }

    /**
     * Substack ends a paid post's opening in its feed with "Read more" back to the post: however
     * long the opening, it's an excerpt, so the page is fetched and its paywall seen.
     */
    @Test
    fun aLongFeedTextEndingInReadMoreIsAnExcerpt() = runTest {
        val opening = "<p>${sentence.repeat(30)}</p><p><a href=\"$url\">Read more</a></p>"
        val http = FakeHttp(mapOf(url to paywalledPage(100)))
        val article = ArticleExtractor(http).extract(input(opening))
        assertEquals(listOf(url), http.requested)
        assertTrue(article.paidPost)
        assertTrue(article.usedFeedContent)
        assertFalse(article.html.contains("Read more"))
        val free = ArticleExtractor(FakeHttp(mapOf(url to page()))).extract(input(opening))
        assertFalse("an excerpt feed's page is the whole post", free.paidPost)
    }

    /** A link post's pitch is the pitcher's own words, free whatever the story's site does. */
    @Test
    fun aLinkPostsPitchIsntAPaidPost() = runTest {
        val story = "https://other.example.com/story"
        val pitchHtml = "<p>${sentence.repeat(3)}</p>"
        val article = ArticleExtractor(FakeHttp(mapOf(story to paywalledPage(20).let { HttpResponse(200, story, it.contentType, it.body) })))
            .extract(ExtractInput(story, "The Quiet Joy of Reading Slowly", pitchHtml, null, feedUrl = url))
        assertTrue(article.usedFeedContent)
        assertFalse(article.paidPost)
    }
}
