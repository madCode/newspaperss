package com.app.newspaperss.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.testutil.testCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.app.newspaperss.core.ttrss.TtrssCategory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TtrssSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val server = FakeTtrss(http)
    private val now = Instant.parse("2026-09-29T06:00:00Z")
    private val accounts by lazy { TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher()) }
    private val sources = SourceRepository(db)
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources) }
    private val sync by lazy { FeedSync(db, http, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(7), accounts) }

    private suspend fun connect(): SourceEntity {
        assertNull(ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        return db.sources().ofKind(SourceKind.TTRSS).single()
    }

    @Test
    fun connectingChecksTheLoginAndAddsOneSource() = runTest {
        val source = connect()
        assertEquals(server.apiUrl, source.url)
        assertEquals(SourceRepository.TTRSS_TITLE, source.title)
        assertEquals(StoredAccount.Ready(TtrssAccount(server.apiUrl, "reader", "secret")), accounts.load())
        assertEquals("the test login is closed again", listOf("login", "logout"), server.ops)
    }

    @Test
    fun aRejectedLoginSavesNothingAndSaysWhy() = runTest {
        assertEquals("tt-rss didn't accept that username and password.", ttrss.connect("rss.example.com/tt-rss", "reader", "wrong"))
        server.apiEnabled = false
        assertTrue(ttrss.connect("rss.example.com/tt-rss", "reader", "secret")!!.startsWith("Enable the API in tt-rss preferences"))
        http.unreachable += server.apiUrl
        assertEquals(
            "Couldn't reach rss.example.com/tt-rss. Check the address and your connection.",
            ttrss.connect("rss.example.com/tt-rss", "reader", "secret"),
        )
        assertEquals(StoredAccount.None, accounts.load())
        assertTrue(db.sources().all().isEmpty())
    }

    @Test
    fun connectingAnotherAccountReplacesTheFirst() = runTest {
        val first = connect()
        db.articles().insertNew(listOf(ArticleEntity(sourceId = first.id, guid = "ttrss:1", url = "https://news.example/1", title = "Old")))
        FakeTtrss(http, apiUrl = "https://other.example/api/")
        assertNull(ttrss.connect("https://other.example", "reader", "secret"))
        assertEquals(listOf("https://other.example/api/"), db.sources().all().map { it.url })
        assertEquals("https://other.example/api/", (accounts.load() as StoredAccount.Ready).account.apiUrl)
    }

    @Test
    fun unreadArticlesArriveOnceWithTheirPublication() = runTest {
        val source = connect()
        server.add(12345, "Rates rise", feedId = 111, feedTitle = "Example News")
        server.add(12346, "A long walk", feedId = 7, feedTitle = "A Blog")

        assertEquals(SyncResult(newArticles = 2, failedSources = 0, sources = 1), sync.syncAll())
        assertEquals("still unread on the server, but already known", 0, sync.syncAll().newArticles)
        assertEquals(2, db.articles().allForSource(source.id).size)

        val article = db.articles().allForSource(source.id).single { it.guid == "ttrss:12345" }
        assertEquals("Rates rise", article.title)
        assertEquals("https://news.example/12345", article.url)
        assertEquals("<p>Text of Rates rise.</p>", article.feedHtml)
        assertEquals("111", article.originId)
        assertEquals("Example News", article.originTitle)
        assertEquals(Instant.ofEpochSecond(1_759_125_600L), article.published)
        assertNull("a blank author is no author", article.author)
        assertNull(db.sources().byId(source.id)!!.lastError)
        assertEquals(SourceRepository.TTRSS_TITLE, db.sources().byId(source.id)!!.title)
        assertEquals("each sync logs out again", server.ops.count { it == "login" }, server.ops.count { it == "logout" })
    }

    @Test
    fun problemsShowOnTheSourceAndClearOnSuccess() = runTest {
        val source = connect()
        server.password = "changed"
        sync.syncAll()
        assertEquals("tt-rss didn't accept that username and password.", db.sources().byId(source.id)!!.lastError)

        server.password = "secret"
        server.failWith = 502
        sync.syncAll()
        assertEquals("The tt-rss server answered with error 502.", db.sources().byId(source.id)!!.lastError)

        server.failWith = null
        http.unreachable += server.apiUrl
        sync.syncAll()
        assertEquals("Couldn't reach tt-rss.", db.sources().byId(source.id)!!.lastError)

        http.unreachable.clear()
        sync.syncAll()
        assertNull(db.sources().byId(source.id)!!.lastError)
    }

    @Test
    fun aSourceWithoutASavedLoginAsksToSignInAgain() = runTest {
        val source = connect()
        accounts.clear()
        assertEquals(1, sync.syncAll().failedSources)
        assertEquals(FeedSync.SIGN_IN_AGAIN, db.sources().byId(source.id)!!.lastError)
    }

    @Test
    fun ttrssAndFeedSourcesSyncSideBySide() = runTest {
        connect()
        server.add(1, "From tt-rss", feedId = 1, feedTitle = "Example News")
        sources.addFeed("https://blog.example/feed", "Blog")
        http.page("https://blog.example/feed", rss("Blog", "b1" to "From a feed"))
        assertEquals(SyncResult(newArticles = 2, failedSources = 0, sources = 2), sync.syncAll())
    }

    @Test
    fun unpickedTtrssArticlesExpireLikeFeedArticles() = runTest {
        val source = connect()
        db.articles().insertNew(
            listOf(ArticleEntity(sourceId = source.id, guid = "ttrss:1", url = "https://news.example/1", title = "Old", discoveredAt = now.minus(Duration.ofDays(8)))),
        )
        sync.syncAll()
        assertFalse(db.articles().candidates().any { it.guid == "ttrss:1" })
    }

    @Test
    fun deliveredArticlesAreMarkedReadOnTheServer() = runTest {
        val source = connect()
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        server.add(11, "Two", feedId = 2, feedTitle = "A Blog")
        server.add(12, "Not picked", feedId = 2, feedTitle = "A Blog")
        sync.syncAll()
        val editionId = editionWith(source.id, "ttrss:10", "ttrss:11")

        assertTrue(ttrss.markRead(editionId))
        assertEquals(listOf(10L, 11L), server.markedRead.sorted())
        assertEquals(listOf(12L), server.unread.map { it.id })
        assertEquals(server.ops.count { it == "login" }, server.ops.count { it == "logout" })
    }

    /** A story already delivered from a feed isn't delivered again, and tt-rss hears it's been read rather than keeping it unread for good. */
    @Test
    fun aLinkDeliveredFromAFeedIsMarkedReadInTtrssInsteadOfArrivingAgain() = runTest {
        connect()
        val feed = sources.addFeed("https://news.example/feed", "News")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "n10", url = "https://news.example/10", title = "One")))
        EditionRepository(db, tmp.root, Clock.fixed(now, ZoneOffset.UTC)).markDelivered(editionWith(feed, "n10"))
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        server.add(11, "Two", feedId = 1, feedTitle = "Example News")

        assertEquals(1, sync.syncAll().newArticles)
        assertEquals(listOf(10L), server.markedRead)
    }

    @Test
    fun aSourceSetToACategoryTakesOnlyItsArticles() = runTest {
        val source = connect()
        server.categories[5] = "Tech"
        server.add(10, "Gadget", feedId = 1, feedTitle = "Tech News", categoryId = 5)
        server.add(11, "Recipe", feedId = 2, feedTitle = "Food", categoryId = 0)

        val tech = (ttrss.categories() as TtrssRepository.Categories.Loaded).categories.single { it.title == "Tech" }
        ttrss.chooseCategory(source.id, tech)
        sync.syncAll()

        assertEquals(listOf("Gadget"), db.articles().allForSource(source.id).map { it.title })
        assertEquals("Tech", db.sources().byId(source.id)!!.ttrssCategoryTitle)
    }

    @Test
    fun choosingACategoryDropsArticlesWaitingFromBefore() = runTest {
        val source = connect()
        server.add(11, "Recipe", feedId = 2, feedTitle = "Food")
        sync.syncAll()
        val broughtBack = db.articles().allForSource(source.id).single()
        server.add(12, "Soup", feedId = 2, feedTitle = "Food")
        sync.syncAll()
        db.articles().bringBack(listOf(broughtBack.id))

        ttrss.chooseCategory(source.id, TtrssCategory(5, "Tech"))

        assertEquals("the reader's own bring-back stays", listOf("Recipe"), db.articles().candidates().map { it.title })
    }

    /** tt-rss answers a missing category with an empty list, which must not look like a quiet day. */
    @Test
    fun aCategoryThatsGoneIsReportedOnTheSource() = runTest {
        val source = connect()
        ttrss.chooseCategory(source.id, TtrssCategory(9, "Deleted"))

        assertEquals(1, sync.syncAll().failedSources)
        assertEquals(FeedSync.CATEGORY_GONE, db.sources().byId(source.id)!!.lastError)
    }

    @Test
    fun connectingAgainStartsFromAllUnread() = runTest {
        val source = connect()
        ttrss.chooseCategory(source.id, TtrssCategory(0, "Uncategorized"))
        connect()
        assertNull(db.sources().byId(source.id)!!.ttrssCategoryId)
    }

    @Test
    fun anAccountSetToLeaveArticlesUnreadIsntToldWhatWasDelivered() = runTest {
        val source = connect()
        ttrss.setMarkRead(source.id, false)
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        sync.syncAll()
        val editionId = editionWith(source.id, "ttrss:10")
        var enqueued = false
        EditionRepository(db, tmp.root, Clock.fixed(now, ZoneOffset.UTC)) { enqueued = true }.markDelivered(editionId)

        assertFalse(enqueued)
        assertTrue(ttrss.markRead(editionId))
        assertTrue(server.markedRead.isEmpty())
        assertEquals("already known, so not offered again", 0, sync.syncAll().newArticles)

        ttrss.forget(db.sources().byId(source.id)!!)
        connect()
        assertEquals("nor after connecting again", 0, sync.syncAll().newArticles)
    }

    @Test
    fun aFailedMarkReadIsNotedOnTheSourceAndRetried() = runTest {
        val source = connect()
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        sync.syncAll()
        val editionId = editionWith(source.id, "ttrss:10")

        http.unreachable += server.apiUrl
        assertFalse("unreachable is worth retrying", ttrss.markRead(editionId))
        assertEquals("Delivered articles weren't marked read in tt-rss. Couldn't reach tt-rss.", db.sources().byId(source.id)!!.serverNote)
        sync.syncAll()
        assertNotNull("a successful sync doesn't hide it", db.sources().byId(source.id)!!.serverNote)

        http.unreachable.clear()
        server.password = "changed"
        assertTrue("a rejected login won't fix itself", ttrss.markRead(editionId))
        assertTrue(server.markedRead.isEmpty())

    }

    @Test
    fun anEditionWithoutTtrssArticlesNeedsNothing() = runTest {
        val feed = sources.addFeed("https://blog.example/feed", "Blog")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "b1", url = "https://blog.example/b1", title = "B")))
        val editionId = editionWith(feed, "b1")
        server.ops.clear()
        assertTrue(ttrss.markRead(editionId))
        assertTrue(server.ops.isEmpty())
    }

    private suspend fun editionWith(sourceId: Long, vararg guids: String): Long {
        val editionId = db.editions().insert(EditionEntity(title = "Tuesday", status = EditionStatus.READY))
        val articles = db.articles().allForSource(sourceId).filter { it.guid in guids }
        db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = editionId, articleId = a.id, position = i, title = a.title, sourceTitle = "x", minutes = 1.0) })
        return editionId
    }
}
