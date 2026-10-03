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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

    /** Lets other work run, some of it on other threads, until [condition] is false. */
    private suspend fun idleWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (condition()) {
            check(System.currentTimeMillis() < deadline) { "still waiting" }
            Thread.sleep(5)
            yield()
        }
    }

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
        assertEquals(FeedSync.SIGN_IN_AGAIN, ttrss.unsubscribe(1, null))
    }

    @Test
    fun undoUnsubscribesAndLetsGoOfWhatItBroughtMeanwhile() = runTest {
        val account = connect()
        val added = ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Added
        val id = added.feedId!!
        // tt-rss fetched it, and a sync brought its articles, before Undo was tapped.
        server.fetch(id)
        server.add(900, "A new comet", feedId = id, feedTitle = "science.example", categoryId = 4)
        server.add(901, "Starred comet", feedId = id, feedTitle = "science.example", categoryId = 4)
        sync.sync(db.sources().byId(account.id)!!)
        val starred = db.articles().allForSource(account.id).single { it.guid == "ttrss:901" }
        sources.setStarred(starred.id, true)

        assertNull(ttrss.unsubscribe(id, added.login))

        assertEquals(listOf(id), server.unsubscribed)
        assertFalse(id in server.feeds)
        assertFalse("gone from Sources", db.sources().publication(account.id, id.toString())!!.listed)
        assertNull(ttrss.feedAt(feedUrl))
        assertEquals(ArticleState.EXPIRED, db.articles().allForSource(account.id).single { it.guid == "ttrss:900" }.state)
        assertTrue("one a sync still running brings in after this is skipped by the paper", db.sources().publication(account.id, id.toString())!!.leftOut)
        assertEquals("a starred one stays", ArticleState.NEW, db.articles().allForSource(account.id).single { it.guid == "ttrss:901" }.state)
    }

    @Test
    fun undoingAFeedAlreadyGoneFromTtrssIsDone() = runTest {
        connect()
        assertNull(ttrss.unsubscribe(4242, null))
    }

    @Test
    fun anotherLoginSigningInMidSubscribeIsntGivenTheFirstOnesFeeds() = runTest {
        connect()
        val gate = CompletableDeferred<Unit>()
        server.subscribeGate = gate
        val pending = async { ttrss.subscribe(feedUrl, science) }
        idleWhile { server.subscribed.isEmpty() }
        server.user = "someone-else"
        assertNull(ttrss.connect("rss.example.com/tt-rss", "someone-else", server.password))
        gate.complete(Unit)
        val added = pending.await() as TtrssRepository.Subscribed.Added

        val account = db.sources().ofKind(SourceKind.TTRSS).single()
        assertNull("not listed with the first login's client", account.feedsListedAt)
        assertTrue(db.sources().observePublicationsOf(account.id).first().isEmpty())
        assertEquals("You've signed in to tt-rss again since. Remove it in tt-rss itself.", ttrss.unsubscribe(added.feedId!!, added.login))
        assertTrue(server.unsubscribed.isEmpty())
    }

    @Test
    fun withoutAnIdUndoNeverPointsAtAFeedTheReaderAlreadyHad() = runTest {
        connect()
        // Already in tt-rss under the http address, subscribed after today's list.
        server.feeds[8] = FakeTtrss.Feed("Science, older", "http://science.example/feed", categoryId = 4)
        server.subscribeWithoutId = true
        val added = ttrss.subscribe(feedUrl, science) as TtrssRepository.Subscribed.Added
        assertEquals(server.feeds.entries.single { it.value.url == feedUrl }.key, added.feedId)
    }

    @Test
    fun aFeedUnsubscribedInTtrssLongAgoDoesntCountAsThere() = runTest {
        val account = connect()
        db.sources().savePublication(PublicationEntity(account.id, "3", title = "Gone", feedUrl = feedUrl, category = "Science", listed = false))
        // A category change clears the list's time until the next check.
        db.sources().setTtrssCategory(account.id, 5, "News")
        assertNull(ttrss.feedAt(feedUrl))
    }
}
