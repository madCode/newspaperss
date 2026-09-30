package com.app.newspaperss.data

import kotlinx.coroutines.flow.first
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
    private val sources by lazy { SourceRepository(db, Clock.fixed(now, ZoneOffset.UTC)) }
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
        http.unreachable.clear()
        http.timingOut += server.apiUrl
        assertEquals("rss.example.com/tt-rss took too long to answer. Try again in a moment.", ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
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
    fun aQuietFeedIsntCrowdedOutByABusyOne() = runTest {
        val source = connect()
        server.add(1, "A monthly essay", feedId = 7, feedTitle = "Quarterly Review")
        (2L..301L).forEach { server.add(it, "Headline $it", feedId = 111, feedTitle = "Busy News") }

        sync.syncAll()

        val articles = db.articles().allForSource(source.id)
        assertTrue(articles.any { it.title == "A monthly essay" })
        assertEquals(
            "the busy feed's newest few",
            (297L..301L).map { "ttrss:$it" }.toSet(),
            articles.filter { it.originId == "111" }.map { it.guid }.toSet(),
        )
    }

    @Test
    fun aLeftOutFeedIsntFetchedAndStaysListedByName() = runTest {
        val source = connect()
        server.add(1, "An essay", feedId = 7, feedTitle = "Quarterly Review")
        server.add(2, "A press release", feedId = 42, feedTitle = "Press Office")
        sync.syncAll()
        val press = sources.observeFeeds(source.id).first().single { it.originId == "42" }
        sources.setFeedInPaper(source.id, press, inPaper = false)
        server.add(3, "Another press release", feedId = 42, feedTitle = "Press Office")

        sync.syncAll()

        assertTrue(db.articles().allForSource(source.id).none { it.title == "Another press release" })
        assertEquals("nothing left waiting in vain", ArticleState.EXPIRED, db.articles().allForSource(source.id).single { it.title == "A press release" }.state)
        assertEquals(
            listOf(FeedChoice("42", "Press Office", inPaper = false), FeedChoice("7", "Quarterly Review", inPaper = true)),
            sources.observeFeeds(source.id).first(),
        )
    }

    @Test
    fun theFeedListShowsRecentFeedsUnderTheirLatestName() = runTest {
        val source = connect()
        fun article(guid: String, feed: String, title: String, daysAgo: Long) =
            ArticleEntity(sourceId = source.id, guid = guid, url = "https://news.example/$guid", title = guid, originId = feed, originTitle = title, discoveredAt = now.minus(Duration.ofDays(daysAgo)))
        db.articles().insertNew(listOf(article("a", "1", "Zeit Online", 10), article("b", "1", "Die Zeit", 2), article("c", "2", "Unsubscribed long ago", 60)))
        assertEquals(listOf(FeedChoice("1", "Die Zeit", inPaper = true)), sources.observeFeeds(source.id).first())
    }

    @Test
    fun leftOutFeedsStayForTheSameUserAndGoForAnother() = runTest {
        // Feed ids belong to each tt-rss user; another user on the same server has their own.
        val source = connect()
        sources.setFeedInPaper(source.id, FeedChoice("42", "Press Office", inPaper = true), inPaper = false)

        assertNull(ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        assertEquals(listOf("42"), db.sources().allLeftOut().map { it.originId })

        server.user = "partner"
        assertNull(ttrss.connect("rss.example.com/tt-rss", "partner", "secret"))
        assertTrue(db.sources().allLeftOut().isEmpty())
    }

    @Test
    fun oneFeedTtrssCantServeDoesntCostTheOthers() = runTest {
        val source = connect()
        server.add(1, "Fine", feedId = 7, feedTitle = "A Blog")
        server.add(2, "Broken", feedId = 8, feedTitle = "Bad Feed")
        server.brokenFeed = 8

        sync.syncAll()

        assertEquals(listOf("Fine"), db.articles().allForSource(source.id).map { it.title })
        assertNull(db.sources().byId(source.id)!!.lastError)
    }

    @Test
    fun aCategorysSubcategoryFeedsArriveButNotAFeedSharingItsId() = runTest {
        val source = connect()
        server.categories[1] = "Ideas"
        server.categories[3] = "Essays"
        server.subcategories[3] = 1
        server.add(1, "In Ideas", feedId = 20, feedTitle = "Ideas Feed", categoryId = 1)
        server.add(2, "In Essays", feedId = 50, feedTitle = "Essays Feed", categoryId = 3)
        // Feed ids and category ids are separate sequences: this feed shares the subcategory's id.
        server.add(3, "Unrelated", feedId = 3, feedTitle = "Elsewhere", categoryId = 0)
        ttrss.chooseCategory(source.id, TtrssCategory(1, "Ideas"))

        sync.syncAll()

        assertEquals(setOf("In Ideas", "In Essays"), db.articles().allForSource(source.id).map { it.title }.toSet())
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

        // A slow home server isn't a connection problem, and shouldn't send the reader to check one.
        http.unreachable.clear()
        http.timingOut += server.apiUrl
        sync.syncAll()
        assertEquals("tt-rss took too long to answer. It'll be tried again at the next sync.", db.sources().byId(source.id)!!.lastError)

        http.timingOut.clear()
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

    /** A pick read through tt-rss is the story it points to, like one from the feed itself, so a story already delivered isn't sent again. */
    @Test
    fun aTtrssLinkPostIsItsStoryAndIsMarkedReadIfTheStoryWasDelivered() = runTest {
        val source = connect()
        val feed = sources.addFeed("https://other.example/feed", "Other")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "o1", url = "https://story.example/delivered", title = "Delivered")))
        EditionRepository(db, tmp.root, Clock.fixed(now, ZoneOffset.UTC)).markDelivered(editionWith(feed, "o1"))
        fun pitchFor(link: String) = "<p>A short pitch.</p><p><a href=\"$link\">Read the story</a></p>"
        server.unread += FakeTtrss.Item(20, "Pick", feedId = 1, feedTitle = "News", content = pitchFor("https://story.example/new?src=news"))
        server.unread += FakeTtrss.Item(21, "Old pick", feedId = 1, feedTitle = "News", content = pitchFor("https://story.example/delivered?src=news"))

        assertEquals(1, sync.syncAll().newArticles)

        val pick = db.articles().allForSource(source.id).single()
        assertEquals("https://story.example/new", pick.url)
        assertEquals("https://news.example/20", pick.viaUrl)
        assertEquals(listOf(21L), server.markedRead)
    }

    /**
     * A link remembered as delivered with its tracking tags (as it went out) is still recognised
     * once stored links lose them: tt-rss is told it's read, and it isn't stored to go out again.
     */
    @Test
    fun aLinkDeliveredWithItsTrackingTagsIsMarkedReadAndNotStoredAgain() = runTest {
        val source = connect()
        db.openHelper.writableDatabase.execSQL("INSERT INTO delivered_urls (url, deliveredAt) VALUES ('https://news.example/30?utm_source=rss', 0)")
        server.unread += FakeTtrss.Item(30, "Sent before", feedId = 1, feedTitle = "News", link = "https://news.example/30?utm_source=rss")

        assertEquals(0, sync.syncAll().newArticles)

        assertEquals(listOf(30L), server.markedRead)
        assertTrue(db.articles().allForSource(source.id).isEmpty())
    }

    /**
     * A tt-rss copy of a story that went out from another source isn't offered again, and is
     * marked read on the server along with the edition's own tt-rss articles.
     */
    @Test
    fun aTtrssCopyOfALinkDeliveredFromAFeedIsUsedUpAndMarkedRead() = runTest {
        val source = connect()
        server.add(40, "Shared story", feedId = 1, feedTitle = "News")
        sync.syncAll()
        val feed = sources.addFeed("https://other.example/feed", "Other")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "o40", url = "https://news.example/40", title = "Shared story")))
        val editionId = editionWith(feed, "o40")

        EditionRepository(db, tmp.root, Clock.fixed(now, ZoneOffset.UTC)).markDelivered(editionId)

        assertTrue("not offered again", db.articles().candidates().none { it.sourceId == source.id })
        assertTrue(ttrss.markRead(editionId))
        assertEquals(listOf(40L), server.markedRead)
    }

    /** Marking read runs later, in a worker: a used-up copy the reader has starred since is going out again, so it stays unread. */
    @Test
    fun aTtrssCopyStarredSinceDeliveryIsntMarkedRead() = runTest {
        val source = connect()
        server.add(41, "Shared story", feedId = 1, feedTitle = "News")
        sync.syncAll()
        val feed = sources.addFeed("https://other.example/feed", "Other")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "o41", url = "https://news.example/41", title = "Shared story")))
        val editionId = editionWith(feed, "o41")
        EditionRepository(db, tmp.root, Clock.fixed(now, ZoneOffset.UTC)).markDelivered(editionId)

        assertTrue(db.articles().setStarred(db.articles().allForSource(source.id).single().id, true, now))
        assertTrue(ttrss.markRead(editionId))

        assertTrue(server.markedRead.isEmpty())
    }

    /** Marking read in the app reaches tt-rss at the next sync, so an Undo before then never has to. */
    @Test
    fun articlesMarkedReadInTheAppAreMarkedReadInTtrssAtTheNextSync() = runTest {
        val source = connect()
        server.add(10, "Read it", feedId = 1, feedTitle = "Example News")
        server.add(11, "Undone", feedId = 1, feedTitle = "Example News")
        server.add(12, "Still waiting", feedId = 1, feedTitle = "Example News")
        sync.syncAll()
        val byGuid = db.articles().allForSource(source.id).associateBy { it.guid }
        sources.markRead(listOf(byGuid.getValue("ttrss:10").id))
        sources.undoMarkRead(sources.markRead(listOf(byGuid.getValue("ttrss:11").id)).marked)
        assertTrue("nothing is sent when marked", server.markedRead.isEmpty())

        sync.syncAll()

        assertEquals(listOf(10L), server.markedRead)
        assertEquals(ArticleState.SKIPPED, db.articles().byId(byGuid.getValue("ttrss:10").id)!!.state)
    }

    @Test
    fun anArticleMarkedReadReachesTtrssEvenOncePushedOutOfItsFeedsFewNewestAndOnlyOnce() = runTest {
        val source = connect()
        server.add(10, "Read it", feedId = 1, feedTitle = "Example News")
        sync.syncAll()
        sources.markRead(listOf(db.articles().allForSource(source.id).single().id))
        (11L..16L).forEach { server.add(it, "Newer $it", feedId = 1, feedTitle = "Example News") }

        sync.syncAll()
        assertEquals(listOf(10L), server.markedRead)

        sync.syncAll()
        assertEquals("not sent again", listOf(10L), server.markedRead)
    }

    @Test
    fun anAccountSetToLeaveArticlesUnreadIsntToldWhatWasMarkedRead() = runTest {
        val source = connect()
        ttrss.setMarkRead(source.id, false)
        server.add(10, "Read it", feedId = 1, feedTitle = "Example News")
        sync.syncAll()
        sources.markRead(listOf(db.articles().allForSource(source.id).single().id))

        sync.syncAll()

        assertTrue(server.markedRead.isEmpty())
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
        val starred = db.articles().allForSource(source.id).single()
        server.add(12, "Soup", feedId = 2, feedTitle = "Food")
        sync.syncAll()
        sources.setStarred(starred.id, true)

        ttrss.chooseCategory(source.id, TtrssCategory(5, "Tech"))

        assertEquals("the reader's own star stays", listOf("Recipe"), db.articles().candidates().map { it.title })
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

    @Test
    fun startingFreshCatchesUpTtrssAndLeavesWhatsWaitingHere() = runTest {
        val source = connect()
        db.articles().insertNew(listOf(ArticleEntity(sourceId = source.id, guid = "ttrss:1", url = "https://news.example/1", title = "Waiting")))

        assertNull(ttrss.startFresh(source.id))

        assertEquals(listOf(Triple(-4, false, "2week")), server.caughtUp)
        assertEquals(listOf("login", "catchupFeed", "logout"), server.ops.takeLast(3))
        assertEquals(ArticleState.NEW, db.articles().allForSource(source.id).single().state)
    }

    @Test
    fun startingFreshNeverReachesAServerTheSourceIsntFrom() = runTest {
        // The saved login moved to another server but this source stayed: its category id, or
        // "everything", would mean someone else's articles there.
        val source = connect()
        accounts.save(TtrssAccount("https://other.example/api/", "reader", "secret"))
        assertEquals(FeedSync.SIGN_IN_AGAIN, ttrss.startFresh(source.id))
        assertTrue(server.caughtUp.isEmpty())
    }

    @Test
    fun startingFreshKeepsToTheChosenCategory() = runTest {
        val source = connect()
        ttrss.chooseCategory(source.id, TtrssCategory(7, "Ideas"))
        assertNull(ttrss.startFresh(source.id))
        assertEquals(listOf(Triple(7, true, "2week")), server.caughtUp)
    }

    @Test
    fun anOldServerIsNotAsked() = runTest {
        val source = connect()
        server.apiLevel = 14
        assertEquals("Your tt-rss is too old for this. Update it, or use Mark as read in tt-rss itself.", ttrss.startFresh(source.id))
        assertTrue(server.caughtUp.isEmpty())
    }

    @Test
    fun aConnectTimeoutIsAConnectionProblemNotASlowServer() = runTest {
        // A switched-off VPN or a wrong address behind a firewall times out connecting.
        assertEquals("Couldn't reach tt-rss.", FeedSync.ttrssUnreachable(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("tt-rss took too long to answer.", FeedSync.ttrssUnreachable(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun startingFreshThatTimesOutDoesntPromiseARetry() = runTest {
        val source = connect()
        http.timingOut += server.apiUrl
        assertEquals("tt-rss took too long to answer. It may still be working through it: check in tt-rss before trying again.", ttrss.startFresh(source.id))
    }

    @Test
    fun startingFreshWithTtrssUnreachableSaysSo() = runTest {
        val source = connect()
        http.unreachable += server.apiUrl
        assertEquals("Couldn't reach tt-rss.", ttrss.startFresh(source.id))
    }
}
