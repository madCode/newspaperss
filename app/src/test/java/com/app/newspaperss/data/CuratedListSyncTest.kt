package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.lists.ArtsAndLettersDaily
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class CuratedListSyncTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private var now = Instant.parse("2026-09-29T06:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val sync = FeedSync(db, http, clock, keepFor = Duration.ofDays(30), listKeep = 4)
    private val repo = SourceRepository(db)

    /** The shape of aldaily.com's front page, with [day]'s picks at the top of each column. */
    private fun publishDay(day: Int) {
        val columns = listOf("Articles of Note", "New Books", "Essays &amp; Opinions").mapIndexed { i, name ->
            val entries = (day downTo maxOf(1, day - 2)).joinToString("<hr>") { d ->
                "<p>Teaser $d.$i ... <a href=\"https://example.com/$d/$i\">more&nbsp;»</a></p>"
            }
            "<div class=\"col-md-3\"><h2 class=\"column_headers\"><a href=\"/c$i/\">$name</a></h2>$entries</div>"
        }
        http.page(ArtsAndLettersDaily.pageUrl, "<html><body>${columns.joinToString("")}</body></html>")
    }

    private suspend fun waiting(sourceId: Long) =
        db.articles().allForSource(sourceId).filter { it.state == ArticleState.NEW }.map { it.url }

    @Test
    fun eachDaysPicksAreAddedOnceAsPagesCreditedToTheList() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        publishDay(1)

        assertEquals(SyncResult(newArticles = 3, failedSources = 0), sync.syncAll())
        assertEquals(0, sync.syncAll().newArticles)

        val articles = db.articles().allForSource(id)
        assertEquals(listOf("https://example.com/1/0", "https://example.com/1/1", "https://example.com/1/2"), articles.map { it.url })
        assertEquals("<p>Teaser 1.1 ...</p>", articles[1].feedHtml)
        val source = db.sources().byId(id)!!
        assertEquals("Arts & Letters Daily", source.title)
        assertEquals(com.app.newspaperss.core.extract.ContentMode.PAGE, source.contentMode)
        assertNull(source.lastError)
    }

    @Test
    fun onlyTheNewestUnreadLinksAreKept() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        publishDay(1)
        sync.syncAll()
        val delivered = db.articles().allForSource(id).first()
        db.articles().setState(listOf(delivered.id), ArticleState.DELIVERED)

        now = now.plus(Duration.ofDays(1))
        publishDay(2)
        sync.syncAll()

        // Four kept: day 2's three and the newest left of day 1. The delivered one isn't counted or touched.
        assertEquals(
            listOf("https://example.com/1/2", "https://example.com/2/0", "https://example.com/2/1", "https://example.com/2/2"),
            waiting(id),
        )
        assertEquals(ArticleState.EXPIRED, db.articles().byId(delivered.id + 1)!!.state)
        assertEquals(ArticleState.DELIVERED, db.articles().byId(delivered.id)!!.state)
    }

    @Test
    fun aBroughtBackLinkIsNotExpired() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        publishDay(1)
        sync.syncAll()
        val first = db.articles().allForSource(id).first()
        db.articles().setState(listOf(first.id), ArticleState.DELIVERED)
        db.articles().bringBack(listOf(first.id))

        now = now.plus(Duration.ofDays(1))
        publishDay(2)
        sync.syncAll()

        assertTrue(first.url in waiting(id))
    }

    @Test
    fun aChangedLayoutTakesNoLinksAndSaysWhy() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        publishDay(1)
        sync.syncAll()
        http.page(ArtsAndLettersDaily.pageUrl, "<html><body><h1>Our new look</h1><p><a href=\"https://example.com/x\">more »</a></p></body></html>")

        assertEquals(1, sync.syncAll().failedSources)

        assertEquals(FeedSync.LIST_LAYOUT_CHANGED, db.sources().byId(id)!!.lastError)
        assertEquals(3, db.articles().allForSource(id).size)
    }

    @Test
    fun fetchFailuresAreRecordedAndClearedByTheNextSuccess() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        http.page(ArtsAndLettersDaily.pageUrl, "", code = 503)
        sync.syncAll()
        assertEquals("The site answered with error 503.", db.sources().byId(id)!!.lastError)

        http.unreachable += ArtsAndLettersDaily.pageUrl
        sync.syncAll()
        assertEquals("Couldn't reach the site.", db.sources().byId(id)!!.lastError)

        http.unreachable.clear()
        publishDay(1)
        sync.syncAll()
        assertNull(db.sources().byId(id)!!.lastError)
    }

    @Test
    fun aListThisVersionDoesntKnowIsReportedNotFetched() = runTest {
        val id = db.sources().insert(SourceEntity(kind = SourceKind.LIST, url = "newspaperss:list:gone", title = "Gone"))
        sync.syncAll()
        assertEquals(FeedSync.LIST_UNSUPPORTED, db.sources().byId(id)!!.lastError)
    }

    @Test
    fun addingAListTwiceKeepsOneSource() = runTest {
        val id = repo.addList(ArtsAndLettersDaily)
        assertEquals(id, repo.addList(ArtsAndLettersDaily))
        assertEquals(1, db.sources().all().size)
    }
}
