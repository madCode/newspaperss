package com.app.newspaperss.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.testCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Adding a feed to tt-rss and undoing it, below the screens. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TtrssSubscribeTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val server = FakeTtrss(http)
    private val now = Instant.parse("2026-10-03T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val accountData by lazy { PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") } }
    private val accounts by lazy { TtrssAccountStore(accountData, testCipher()) }
    private val sources by lazy { SourceRepository(db, clock) }
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources, clock) }
    private val sync by lazy { FeedSync(db, http, clock, Duration.ofDays(7), accounts) }
    private val science = TtrssCategory(4, "Science")
    private val feedUrl = "https://science.example/feed"

    private suspend fun connect(): SourceEntity {
        server.categories[4] = "Science"
        server.categories[5] = "News"
        server.feeds[7] = FakeTtrss.Feed("Morning Wire", "https://wire.example/rss", categoryId = 5)
        assertNull(ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
        return db.sources().ofKind(SourceKind.TTRSS).single()
    }

    @Test
    fun subscribingListsTheFeedsAtOnceWithTheNewOneWaitingForItsFirstFetch() = runTest {
        val account = connect()
        // Listed this morning: the daily check wouldn't list again until tomorrow.
        sync.sync(account)
        server.ops.clear()

        val added = ttrss.subscribe(feedUrl, science)

        assertEquals(listOf(feedUrl to 4), server.subscribed)
        val id = (added as TtrssRepository.Subscribed.Added).feedId!!
        assertEquals("Science", added.category)
        val publication = db.sources().publication(account.id, id.toString())!!
        assertTrue("listed straight away", publication.listed)
        assertEquals("Science", publication.category)
        assertTrue(publication.awaitingFirstFetch)
        assertEquals("the feed list's time moves on, so the daily check still waits a day", now, db.sources().byId(account.id)!!.feedsListedAt)
        assertEquals("the session is closed", "logout", server.ops.last())
    }

    @Test
    fun aFeedWithArticlesInTtrssIsNoLongerWaiting() = runTest {
        val account = connect()
        val id = (ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Added).feedId!!
        server.fetch(id)
        server.add(900, "A new comet", feedId = id, feedTitle = "science.example", categoryId = 4)

        sync.sync(db.sources().byId(account.id)!!)

        assertFalse(db.sources().publication(account.id, id.toString())!!.awaitingFirstFetch)
    }

    @Test
    fun withoutTheIdFromTtrssItsFoundInTheNewList() = runTest {
        val account = connect()
        server.subscribeWithoutId = true
        val added = ttrss.subscribe("http://www.science.example/feed/", TtrssCategory(0, "Uncategorized")) as TtrssRepository.Subscribed.Added
        assertEquals(server.feeds.entries.single { it.value.url == "http://www.science.example/feed/" }.key, added.feedId)
        assertNull("category 0 is Uncategorized", added.category)
        assertEquals(added.feedId.toString(), ttrss.feedAt(feedUrl)?.key)
        assertEquals(account.id, ttrss.feedAt(feedUrl)?.sourceId)
    }

    @Test
    fun aFeedOutsideThePapersCategorySaysSo() = runTest {
        val account = connect()
        db.sources().setTtrssCategory(account.id, 5, "News")
        val added = ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Added
        assertTrue(added.outsidePaper)
        assertEquals("Science", added.category)
    }

    @Test
    fun alreadySubscribedInTtrssGivesHowItsListedHere() = runTest {
        connect()
        val answer = ttrss.subscribe("https://wire.example/rss", science) as TtrssRepository.Subscribed.Already
        assertEquals("Morning Wire", answer.feed?.title)
        assertEquals("News", answer.feed?.category)
    }

    @Test
    fun aRefusalIsSaidInWords() = runTest {
        connect()
        server.subscribeCode = 5
        assertEquals(TtrssRepository.Subscribed.Failed("tt-rss couldn't download it.", couldntFetch = true), ttrss.subscribe(feedUrl, science))
        server.subscribeCode = null
        server.apiLevel = 4
        assertEquals(TtrssRepository.Subscribed.Failed(TtrssClient.TOO_OLD_TO_SUBSCRIBE), ttrss.subscribe(feedUrl, science))
        assertEquals("never asked an old server", 1, server.subscribed.size)
    }

    @Test
    fun aSignedOutOrUnreachableAccountIsSaidInWords() = runTest {
        connect()
        http.timingOut += server.apiUrl
        assertTrue((ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Failed).reason.startsWith("tt-rss took too long to answer. It may still add it"))
        http.timingOut.clear()
        accounts.clear()
        assertEquals(TtrssRepository.Subscribed.Failed(FeedSync.SIGN_IN_AGAIN), ttrss.subscribe(feedUrl, science))
        assertEquals(FeedSync.SIGN_IN_AGAIN, ttrss.unsubscribe(1))
    }

    @Test
    fun undoUnsubscribesAndLetsGoOfWhatItBroughtMeanwhile() = runTest {
        val account = connect()
        val id = (ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Added).feedId!!
        // tt-rss fetched it, and a sync brought its articles, before Undo was tapped.
        server.fetch(id)
        server.add(900, "A new comet", feedId = id, feedTitle = "science.example", categoryId = 4)
        server.add(901, "Starred comet", feedId = id, feedTitle = "science.example", categoryId = 4)
        sync.sync(db.sources().byId(account.id)!!)
        val starred = db.articles().allForSource(account.id).single { it.guid == "ttrss:901" }
        sources.setStarred(starred.id, true)

        assertNull(ttrss.unsubscribe(id))

        assertEquals(listOf(id), server.unsubscribed)
        assertFalse(id in server.feeds)
        assertFalse("gone from Sources", db.sources().publication(account.id, id.toString())!!.listed)
        assertNull(ttrss.feedAt(feedUrl))
        assertEquals(ArticleState.EXPIRED, db.articles().allForSource(account.id).single { it.guid == "ttrss:900" }.state)
        assertEquals("a starred one stays", ArticleState.NEW, db.articles().allForSource(account.id).single { it.guid == "ttrss:901" }.state)
    }

    @Test
    fun undoingAFeedAlreadyGoneFromTtrssIsDone() = runTest {
        connect()
        assertNull(ttrss.unsubscribe(4242))
    }
}
