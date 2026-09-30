package com.app.newspaperss.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPostsTest {
    /** Longreads' live feed, Sep 29 2026: picks, a weekly top 5, reading lists and an interview. */
    private val longreads = FeedParser.parse(javaClass.getResource("/feeds/longreads.xml")!!.readText(), "https://longreads.com/feed/")

    private fun storyOf(title: String): String? {
        val item = longreads.items.first { it.title.startsWith(title) }
        return LinkPosts.storyUrl(item.url, item.contentHtml, longreads.siteUrl)
    }

    @Test
    fun aLongreadsPickPointsToTheStoryWithoutTheReferralTag() {
        assertEquals("https://www.equator.org/articles/dusklands-coetzee", storyOf("Dusklands"))
        assertEquals("https://www.thesunmagazine.org/articles/608-you-shouldve-said-something", storyOf("You Should"))
    }

    /** The pick links an older Guardian piece for background before the story it's pitching. */
    @Test
    fun aPickWithABackgroundLinkPointsToTheTaggedStory() {
        assertEquals(
            "https://www.theguardian.com/news/ng-interactive/2026/sep/22/a-tale-of-two-trap-houses-being-cuckooed-ruined-my-friends-life-but-did-it-then-help-save-him/",
            storyOf("A Tale of Two Trap Houses"),
        )
    }

    @Test
    fun longreadsOwnWritingStaysAsItIs() {
        // Reading lists and interviews are full text, whatever they link to.
        assertNull(storyOf("Lost and Found: A Reading List"))
        assertNull(storyOf("The Longreads Questionnaire"))
        assertNull(storyOf("Making Meaning, One Step at a Time"))
        assertNull(storyOf("Still On Its Way to Cleveland"))
        // Short, but links only to Longreads itself.
        assertNull(storyOf("The Top 5 Longreads of the Week"))
    }

    @Test
    fun everyTaggedPickInTheFeedIsFound() {
        val found = longreads.items.mapNotNull { LinkPosts.storyUrl(it.url, it.contentHtml, longreads.siteUrl) }
        // 25 items: 6 of Longreads' own (above, and a second week's top 5), 3 picks whose story
        // link has no referral tag.
        assertEquals(16, found.size)
        assertEquals("tracking is gone from every story link", emptyList<String>(), found.filter { "src=" in it })
    }

    @Test
    fun aShortPostLinkingOutWithSomeoneElsesTagIsntALinkPost() {
        // The tag names the site linked to, or a newsletter the author read it in: not this blog.
        val html = """<p>A few thoughts on <a href="https://news.example/story?utm_source=othernewsletter">this story</a>.</p>"""
        assertNull(LinkPosts.storyUrl("https://blog.example/post", html, "https://blog.example/"))
    }

    @Test
    fun aTaggedLinkBackToTheSameSiteIsntALinkPost() {
        val html = """<p>From the archive: <a href="https://shop.longreads.com/item?src=longreads">our tote bag</a>.</p>"""
        assertNull(LinkPosts.storyUrl("https://longreads.com/2026/09/29/tote/", html, "https://longreads.com/"))
    }

    /** A newsletter platform tags every outbound link with its own name, not the newsletter's. */
    @Test
    fun aPlatformsTagOnAHostedNewslettersLinksIsntALinkPost() {
        val html = """<p>Short note. <a href="https://news.example/story?utm_source=substack&amp;utm_medium=email">A story</a>.</p>"""
        assertNull(LinkPosts.storyUrl("https://writer.substack.com/p/note", html, "https://writer.substack.com/"))
    }

    @Test
    fun aLongPostIsntALinkPostWhateverItLinksTo() {
        val words = (1..600).joinToString(" ") { "word$it" }
        val html = """<p>$words</p><p><a href="https://news.example/story?src=blog">Read it</a></p>"""
        assertNull(LinkPosts.storyUrl("https://blog.example/post", html))
    }

    @Test
    fun aFeedServedFromAnotherHostStillCreditsItsSite() {
        val html = """<p>Our pick. <a href="https://news.example/story?ref=bestpicks">Read the story</a></p>"""
        assertEquals("https://news.example/story", LinkPosts.storyUrl("https://feeds.example.net/item/1", html, "https://bestpicks.example/"))
    }
}
