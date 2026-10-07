package com.app.newspaperss.data

import com.app.newspaperss.testutil.TestDataStores
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.testCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Moving phone feeds into tt-rss, below the screens. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FeedMovesTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val stores = TestDataStores()
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val server = FakeTtrss(http)
    private val now = Instant.parse("2026-10-03T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val accounts by lazy { TtrssAccountStore(stores.preferences("ttrss"), testCipher()) }
    private val sources by lazy { SourceRepository(db, clock) }
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources, clock) }
    private val sync by lazy { FeedSync(db, http, clock, Duration.ofDays(7), accounts) }
    private val movesData by lazy { stores.preferences("moves") }
    private var scheduled = 0
    private val moves by lazy { FeedMoves(movesData, db, ttrss, clock) { scheduled++ } }
    private val science = TtrssCategory(4, "Science")

    private suspend fun connect(): SourceEntity {
        server.categories[4] = "Science"
        assertNull(ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
        return db.sources().ofKind(SourceKind.TTRSS).single()
    }

    private suspend fun phoneFeed(url: String, title: String): SourceEntity = db.sources().byId(sources.addFeed(url, title))!!

    private suspend fun article(sourceId: Long, guid: String, state: ArticleState = ArticleState.NEW, starred: Boolean = false): Long {
        db.articles().insertIgnoring(
            ArticleEntity(sourceId = sourceId, guid = guid, url = "https://posts.example/$guid", title = guid, state = state, starredAt = now.takeIf { starred }),
        )
        return db.articles().allForSource(sourceId).single { it.guid == guid }.id
    }

    private fun feedIdOf(url: String) = server.feeds.entries.single { it.value.url == url }.key

    /** Lets work on other threads run until [condition] is false. */
    private suspend fun idleWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (condition()) {
            check(System.currentTimeMillis() < deadline) { "still waiting" }
            Thread.sleep(5)
            yield()
        }
    }

    @Test
    fun aMoveCarriesEachFeedsSettingsAndKeepsAPhoneFeedOnlyUntilItsStarsAreDelivered() = runTest {
        val account = connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        val quiet = phoneFeed("https://quiet.example/rss", "Quiet Blog")
        db.sources().savePublication(
            PublicationEntity(
                aeon.id, PublicationEntity.OWN, contentMode = ContentMode.FEED, fullTextEvidence = FullTextEvidence.FEED_SHORT,
                fullTextStreak = 3, fullTextDay = 20_360, checkedDay = 20_361, chosenMode = ContentMode.PAGE, maxArticles = 2, skipPaidPosts = true,
            ),
        )
        val starred = article(aeon.id, "starred", starred = true)
        val waiting = article(aeon.id, "waiting")
        article(quiet.id, "sent", ArticleState.DELIVERED)

        assertTrue(moves.start(listOf(aeon.id, quiet.id), science))
        assertEquals("the work that does it is asked for", 1, scheduled)
        moves.run()

        assertEquals(setOf("https://aeon.example/feed" to 4, "https://quiet.example/rss" to 4), server.subscribed.toSet())
        val carried = db.sources().publication(account.id, feedIdOf("https://aeon.example/feed").toString())!!
        assertEquals(ContentMode.PAGE, carried.chosenMode)
        assertEquals(2, carried.maxArticles)
        assertTrue(carried.skipPaidPosts)
        assertEquals(ContentMode.FEED, carried.contentMode)
        assertEquals(FullTextEvidence.FEED_SHORT, carried.fullTextEvidence)
        assertEquals(3, carried.fullTextStreak)
        assertEquals(20_360L, carried.fullTextDay)
        assertEquals(20_361L, carried.checkedDay)
        assertFalse(carried.leftOut)
        assertTrue("the list after the batch has it, with what tt-rss says of it", carried.listed)
        assertEquals("Science", carried.category)

        val state = moves.current()
        assertEquals(2, state.moved)
        assertTrue(state.finished)
        assertTrue(state.failed.isEmpty())
        assertNull("nothing left in it, so it goes at once", db.sources().byId(quiet.id))
        val kept = db.sources().byId(aeon.id)!!
        assertTrue("it fetches nothing more", kept.paused)
        assertTrue("Sources doesn't show it", state.hides(kept))
        assertNotNull(db.articles().byId(starred)?.starredAt)

        db.articles().setState(listOf(waiting), ArticleState.EXPIRED)
        moves.tidy()
        assertNotNull("a star still waiting keeps it", db.sources().byId(aeon.id))

        db.articles().setDelivered(listOf(starred))
        moves.tidy()
        assertNull(db.sources().byId(aeon.id))
        assertTrue(moves.current().retiring.isEmpty())
    }

    @Test
    fun aFeedAlreadyInTtrssIsntSubscribedAgainAndGetsThePhonesSettings() = runTest {
        val account = connect()
        server.feeds[7] = FakeTtrss.Feed("Morning Wire", "https://wire.example/rss", categoryId = 4)
        sync.sync(account)
        // The same feed by sameFeed's lights; tt-rss, matching exactly, would add it again.
        val wire = phoneFeed("http://www.wire.example/rss/", "Morning Wire")
        db.sources().savePublication(PublicationEntity(wire.id, PublicationEntity.OWN, maxArticles = 1))
        // tt-rss has this one, but no list has said so here: its own "already subscribed" tells.
        server.feeds[8] = FakeTtrss.Feed("Night Owl", "https://owl.example/feed")
        val owl = phoneFeed("https://owl.example/feed", "Night Owl")
        sources.setPaused(owl.id, true)
        article(owl.id, "owl-waiting")

        moves.start(listOf(wire.id, owl.id), science)
        moves.run()

        assertEquals(listOf("https://owl.example/feed" to 4), server.subscribed)
        assertEquals(1, db.sources().publication(account.id, "7")!!.maxArticles)
        val owlThere = db.sources().publication(account.id, "8")!!
        assertTrue("paused here, so left out there", owlThere.leftOut)
        assertNull("its waiting articles go, which the paper wasn't taking either, so nothing keeps it", db.sources().byId(owl.id))
        assertEquals(2, moves.current().moved)
    }

    @Test
    fun whatTtrssRefusesStaysOnThePhoneAndMovingItAgainTakesOnlyThat() = runTest {
        connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        val blocked = phoneFeed("https://blocked.example/feed", "Blocked")
        server.refuse["https://blocked.example/feed"] = 5

        moves.start(listOf(aeon.id, blocked.id), science)
        moves.run()

        val partial = moves.current()
        assertEquals(1, partial.moved)
        assertEquals(2, partial.total)
        assertEquals(listOf(FeedMoves.Failure(blocked.id, "tt-rss couldn't download it.")), partial.failed)
        assertFalse(db.sources().byId(blocked.id)!!.paused)
        assertFalse(partial.hides(db.sources().byId(blocked.id)!!))

        server.refuse.clear()
        server.subscribed.clear()
        assertTrue(moves.start(listOf(blocked.id), science))
        moves.run()

        assertEquals(listOf("https://blocked.example/feed" to 4), server.subscribed)
        val retried = moves.current()
        assertEquals(1, retried.moved)
        assertEquals(1, retried.total)
        assertTrue(retried.failed.isEmpty())
    }

    @Test
    fun whenTtrssStopsAnsweringTheRestAreGivenUpTogether() = runTest {
        val account = connect()
        val first = phoneFeed("https://first.example/feed", "First")
        val second = phoneFeed("https://second.example/feed", "Second")
        val third = phoneFeed("https://third.example/feed", "Third")
        server.afterSubscribe = { http.unreachable += server.apiUrl }

        moves.start(listOf(first.id, second.id, third.id), science)
        moves.run()

        val state = moves.current()
        assertEquals(listOf(second.id, third.id), state.failed.map { it.sourceId })
        assertTrue(state.failed.all { it.reason == "Couldn't reach tt-rss." })
        assertEquals("what moved stays moved, by the id tt-rss gave, though the list couldn't be read", 1, state.moved)
        assertNotNull(db.sources().publication(account.id, feedIdOf("https://first.example/feed").toString()))
        assertNull("moved, with nothing left in it", db.sources().byId(first.id))
        assertFalse(db.sources().byId(second.id)!!.paused)
    }

    @Test
    fun onAServerThatDoesntSayTheIdTheNewFeedIsFoundNotOneAlreadyThere() = runTest {
        val account = connect()
        server.subscribeWithoutId = true
        // Already in tt-rss at much the same address, but not listed here yet.
        server.feeds[7] = FakeTtrss.Feed("Aeon (old)", "http://www.aeon.example/feed/")
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        db.sources().savePublication(PublicationEntity(aeon.id, PublicationEntity.OWN, maxArticles = 3))

        moves.start(listOf(aeon.id), science)
        moves.run()

        assertEquals(3, db.sources().publication(account.id, feedIdOf("https://aeon.example/feed").toString())!!.maxArticles)
        assertNull("the feed that was there keeps its own", db.sources().publication(account.id, "7")!!.maxArticles)
        assertEquals(1, moves.current().moved)
    }

    @Test
    fun anotherLoginSigningInMidMoveStopsIt() = runTest {
        val account = connect()
        val first = phoneFeed("https://first.example/feed", "First")
        val second = phoneFeed("https://second.example/feed", "Second")
        db.sources().savePublication(PublicationEntity(first.id, PublicationEntity.OWN, maxArticles = 2))
        server.afterSubscribe = { accounts.save(TtrssAccount(server.apiUrl, "someone-else", "theirs")) }

        moves.start(listOf(first.id, second.id), science)
        moves.run()

        val state = moves.current()
        assertEquals(0, state.moved)
        assertEquals(listOf(first.id, second.id), state.failed.map { it.sourceId })
        assertTrue(state.failed.all { it.reason == TtrssRepository.SIGNED_IN_AGAIN })
        assertEquals("nothing is asked of the other account", 1, server.subscribed.size)
        assertFalse(db.sources().byId(first.id)!!.paused)
        assertTrue("no settings land on another account's feeds", db.sources().observePublicationsOf(account.id).first().none { it.maxArticles == 2 })
    }

    @Test
    fun aMoveCutOffPartWayCarriesOnWithoutAskingAgainAboutFeedsDone() = runTest {
        connect()
        val first = phoneFeed("https://first.example/feed", "First")
        val second = phoneFeed("https://second.example/feed", "Second")
        server.afterSubscribe = { url -> if (url == "https://first.example/feed") server.subscribeGate = CompletableDeferred() }

        moves.start(listOf(first.id, second.id), science)
        assertFalse("a second Move while one is under way does nothing", moves.start(listOf(second.id), science))
        val running = launch { moves.run() }
        idleWhile { server.subscribed.size < 2 }
        // The app dies while tt-rss is fetching the second.
        running.cancelAndJoin()
        assertTrue(moves.current().running)

        server.subscribeGate = null
        server.afterSubscribe = null
        FeedMoves(movesData, db, ttrss) {}.run()

        assertEquals(1, server.subscribed.count { it.first == "https://first.example/feed" })
        val state = moves.current()
        assertEquals(2, state.moved)
        assertTrue(state.failed.isEmpty())
        assertEquals(1, scheduled)
    }

    @Test
    fun leavingTheServerMakesFeedsKeptForTheirStarsPhoneFeedsAgain() = runTest {
        connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        article(aeon.id, "starred", starred = true)
        moves.start(listOf(aeon.id), science)
        moves.run()
        assertTrue(db.sources().byId(aeon.id)!!.paused)

        moves.restore()

        assertFalse(db.sources().byId(aeon.id)!!.paused)
        assertTrue(moves.current().retiring.isEmpty())
    }

    @Test
    fun aFeedResumedFromItsPageIsAPhoneFeedAgainAndNeverDeleted() = runTest {
        connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        moves.start(listOf(aeon.id), science)
        article(aeon.id, "starred", starred = true)
        moves.run()

        sources.setPaused(aeon.id, false)
        db.articles().setDelivered(db.articles().allForSource(aeon.id).map { it.id })
        moves.tidy()

        assertNotNull(db.sources().byId(aeon.id))
        assertTrue(moves.current().retiring.isEmpty())
    }

    @Test
    fun aMovedFeedStaysWhileAnEditionWithItsArticlesCanStillBeMarkedNotSent() = runTest {
        connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        val starred = article(aeon.id, "starred", starred = true)
        moves.start(listOf(aeon.id), science)
        moves.run()
        val edition = db.editions().insert(EditionEntity(title = "Saturday", status = EditionStatus.DELIVERED, deliveredAt = now))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = starred, position = 0, title = "starred", sourceTitle = "Aeon", minutes = 1.0, starred = true)))
        db.articles().setDelivered(listOf(starred))

        moves.tidy()
        assertNotNull("Mark as not sent would bring its star back", db.sources().byId(aeon.id))

        FeedMoves(movesData, db, ttrss, Clock.fixed(now.plus(Duration.ofDays(15)), ZoneOffset.UTC)) {}.tidy()
        assertNull(db.sources().byId(aeon.id))
    }

    @Test
    fun whatTheReaderMarkedReadOnThePhoneDoesntComeBackFromTtrss() = runTest {
        val account = connect()
        val wire = phoneFeed("https://wire.example/rss", "Morning Wire")
        db.articles().insertIgnoring(ArticleEntity(sourceId = wire.id, guid = "read", url = "https://news.example/900", title = "Read already", state = ArticleState.SKIPPED))
        moves.start(listOf(wire.id), science)
        moves.run()
        val feedId = feedIdOf("https://wire.example/rss")
        server.fetch(feedId)
        server.add(900, "Read already", feedId = feedId, feedTitle = "Morning Wire", categoryId = 4)
        server.add(901, "New since", feedId = feedId, feedTitle = "Morning Wire", categoryId = 4)

        sync.sync(db.sources().byId(account.id)!!)

        assertEquals(listOf("New since"), db.articles().allForSource(account.id).map { it.title })
    }

    @Test
    fun aMoveThatCantGoOnIsGivenBackToTheBanner() = runTest {
        connect()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        moves.start(listOf(aeon.id), science)

        moves.giveUp("Something went wrong moving it. Try again.")

        val state = moves.current()
        assertFalse(state.running)
        assertEquals(listOf(FeedMoves.Failure(aeon.id, "Something went wrong moving it. Try again.")), state.failed)
        assertTrue("a new move can start", moves.start(listOf(aeon.id), science))
    }
}
