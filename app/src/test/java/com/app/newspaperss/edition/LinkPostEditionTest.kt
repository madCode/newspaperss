package com.app.newspaperss.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

/** A feed of picks, each a short pitch linking to the story on another site. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class LinkPostEditionTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val clock = Clock.fixed(Instant.parse("2026-09-29T06:30:00Z"), ZoneOffset.UTC)
    private val sources = SourceRepository(db, clock)
    private val evidence = mutableListOf<FullTextEvidence>()
    private val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder()) { _, _, e, _ -> e?.let { evidence += it } }
    private val builder by lazy { EditionBuilder(db, provider, tmp.root, clock, ZoneOffset.UTC) }
    private val editions by lazy { EditionRepository(db, tmp.root, clock) }
    private val sync = FeedSync(db, http, clock)

    private val story = "https://www.equator.example/articles/dusklands"
    private val pitchWords = (1..150).joinToString(" ") { "pitch$it" }

    private fun rss(site: String, vararg items: String) =
        """<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>$site</title><link>https://$site.example/</link>""" +
            items.joinToString("") + "</channel></rss>"

    private fun item(site: String, slug: String, title: String, content: String) =
        "<item><title>$title</title><link>https://$site.example/2026/09/$slug/</link><guid>$site-$slug</guid>" +
            "<content:encoded><![CDATA[$content]]></content:encoded></item>"

    private fun pick(slug: String = "dusklands", link: String = "$story?src=picks") = item(
        "picks", slug, "Dusklands",
        "<p>$pitchWords</p><blockquote><p>An excerpt from the story.</p></blockquote><p><a href=\"$link\">Read the story</a></p>",
    )

    private fun storyPage(words: Int = 2500) {
        val text = (1..words).joinToString(" ") { "story$it" }
        http.page(story, "<html><head><meta property=\"og:site_name\" content=\"Equator\"></head><body><article><h1>Dusklands</h1><p>$text</p></article></body></html>")
    }

    private suspend fun addFeed(site: String, vararg items: String): Long {
        http.page("https://$site.example/feed", rss(site, *items))
        return sources.addFeed("https://$site.example/feed", site.replaceFirstChar { it.uppercase() })
    }

    private suspend fun bookText(editionId: Long): String =
        ZipFile(editions.fileOf(db.editions().byId(editionId)!!)!!).use { zip ->
            zip.entries().toList().filter { it.name.endsWith(".xhtml") }.joinToString("\n") { zip.getInputStream(it).bufferedReader().readText() }
        }

    @Test
    fun aPickBringsTheStoryItPointsToCreditedToBoth() = runTest {
        addFeed("picks", pick())
        storyPage()
        sync.syncAll()

        val built = builder.build(EditionSettings(minutes = 60)) as BuildResult.Built

        val entry = editions.observeArticles(built.editionId).first().single()
        assertEquals("Equator via Picks", entry.sourceTitle)
        assertTrue("about 10 minutes of story, not a minute of pitch", entry.minutes > 9)
        val book = bookText(built.editionId)
        assertTrue("story2500" in book)
        assertFalse("pitch1 " in book)
        assertTrue("the original is the story, without the referral tag", "Read the original at <a href=\"$story\">equator.example</a>" in book)
        assertEquals("the story page against the pitch says nothing about the source's feed", emptyList<FullTextEvidence>(), evidence)
    }

    @Test
    fun aPickWhoseStoryCantBeFetchedStillBringsItsPitch() = runTest {
        addFeed("picks", pick())
        sync.syncAll()

        val built = builder.build(EditionSettings(minutes = 60)) as BuildResult.Built

        val entry = editions.observeArticles(built.editionId).first().single()
        assertEquals("equator.example via Picks", entry.sourceTitle)
        val book = bookText(built.editionId)
        assertTrue("pitch150" in book)
        assertTrue("fetch the full article (error 404)" in book)
        assertTrue("Read the original at <a href=\"$story\">" in book)
    }

    @Test
    fun aReaderWhoChoseTheFeedsTextGetsThePitch() = runTest {
        val id = addFeed("picks", pick())
        sources.chooseContentMode(id, ContentMode.FEED)
        storyPage()
        sync.syncAll()

        val built = builder.build(EditionSettings(minutes = 60)) as BuildResult.Built

        val book = bookText(built.editionId)
        assertTrue("pitch150" in book)
        assertFalse("story2500" in book)
    }

    /**
     * A short post whose one outbound link is tagged with its blog's name (Ghost does that to every
     * link) is still the post when the linked page is about something else: its own words, its
     * own blog's credit and its own page as the original.
     */
    @Test
    fun aCommentaryPostWithOneTaggedLinkStaysThePost() = runTest {
        addFeed(
            "blog",
            item("blog", "leaving", "Why I finally deleted my accounts", "<p>$pitchWords</p><p>As <a href=\"$story?ref=blog.example\">this</a> says.</p>"),
        )
        storyPage()
        sync.syncAll()

        val built = builder.build(EditionSettings(minutes = 60)) as BuildResult.Built

        val entry = editions.observeArticles(built.editionId).first().single()
        assertEquals("Blog", entry.sourceTitle)
        val book = bookText(built.editionId)
        assertTrue("pitch150" in book)
        assertFalse("story2500" in book)
        assertTrue("Read the original at <a href=\"https://blog.example/2026/09/leaving/\">" in book)
    }

    /**
     * Delivering a commentary post that went in as itself delivers the post's page, not the page
     * it linked to: copies of that page waiting elsewhere stay waiting, and tt-rss isn't told
     * they're read.
     */
    @Test
    fun deliveringACommentaryPostLeavesCopiesOfItsLinkedPageWaiting() = runTest {
        val blog = addFeed(
            "blog",
            item("blog", "leaving", "Why I finally deleted my accounts", "<p>$pitchWords</p><p>As <a href=\"$story?ref=blog.example\">this</a> says.</p>"),
        )
        storyPage()
        sync.syncAll()
        val other = sources.addFeed("https://other.example/feed", "Other")
        val ttrss = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = "tt-rss"))
        db.articles().insertNew(
            listOf(
                ArticleEntity(sourceId = other, guid = "o1", url = story, title = "Dusklands"),
                ArticleEntity(sourceId = ttrss, guid = "ttrss:1", url = story, title = "Dusklands"),
            ),
        )
        // Only the post can go in this time.
        sources.setPaused(other, true)
        sources.setPaused(ttrss, true)

        val built = builder.build(EditionSettings(minutes = 60)) as BuildResult.Built
        editions.markDelivered(built.editionId)

        val copies = listOf(other, ttrss).map { db.articles().allForSource(it).single() }
        assertEquals(listOf(ArticleState.NEW, ArticleState.NEW), copies.map { it.state })
        assertTrue("no tt-rss copy is marked read", db.articles().ttrssInEdition(built.editionId).isEmpty())
        assertEquals(listOf("https://blog.example/2026/09/leaving/"), db.articles().deliveredAmong(listOf(story, "https://blog.example/2026/09/leaving/")))
        assertEquals("https://blog.example/2026/09/leaving/", db.articles().allForSource(blog).single().url)
    }

    /** A story two sources picked, one tagging the link with its name and one with analytics tags, goes out once. */
    @Test
    fun aStoryPickedByTwoSourcesGoesOutOnceAndNotAgainFromAThird() = runTest {
        addFeed("picks", pick())
        addFeed("digest", item("digest", "equator", "Dusklands", "<p>$pitchWords</p><p><a href=\"$story\">Go</a></p>").replace(
            "<link>https://digest.example/2026/09/equator/</link>", "<link>$story?utm_source=digest&amp;utm_medium=rss</link>",
        ))
        storyPage()
        sync.syncAll()

        val built = builder.build(EditionSettings(minutes = 120, maxPerSource = 5)) as BuildResult.Built
        assertEquals(1, editions.observeArticles(built.editionId).first().size)
        editions.markDelivered(built.editionId)

        addFeed("later", item("later", "equator", "Dusklands again", "<p>A few words.</p>").replace(
            "<link>https://later.example/2026/09/equator/</link>", "<link>$story</link>",
        ))
        assertEquals("already delivered, so not stored again", 0, sync.syncAll().newArticles)
    }
}
