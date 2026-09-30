package com.app.newspaperss.ui

import org.junit.Assert.assertFalse
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.data.MarkedRead
import com.app.newspaperss.data.EditionEntity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import android.content.Intent
import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.failingLine
import com.app.newspaperss.ui.sources.lastCheckedLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.testCipher
import org.junit.rules.TemporaryFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourceDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = SourceRepository(db)

    @After fun close() = db.close()

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun showsHowTheSourceIsDoingAndWhatHappenedToItsArticles() {
        val id = runBlocking {
            val id = repo.addFeed("https://example.com/feed", "Example")
            db.articles().insertNew(
                listOf(
                    ArticleEntity(sourceId = id, guid = "1", url = "https://example.com/1", title = "Went out"),
                    ArticleEntity(sourceId = id, guid = "2", url = "https://example.com/2", title = "Still waiting"),
                ),
            )
            db.articles().setState(listOf(db.articles().candidates().first { it.guid == "1" }.id), ArticleState.DELIVERED)
            db.sources().recordFailure(id, Instant.now().minus(Duration.ofDays(3)), "Couldn't reach the site.")
            db.sources().recordFailure(id, Instant.now(), "Couldn't reach the site.")
            id
        }
        var back = false
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = { back = true }) }
        idleUntil { visible("Couldn't reach the site.") }

        compose.onNodeWithText("Failing for 3 days", substring = true).assertExists()
        compose.onNodeWithText("Pause").performClick()
        idleUntil { !visible("Failing for") }
        assertNull("time spent paused isn't time spent failing", runBlocking { db.sources().byId(id)!!.failingSince })
        compose.onNodeWithText("Resume").performClick()
        idleUntil { visible("Pause") }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Went out"))
        compose.onNodeWithText("Delivered", substring = true).assertExists()
        list.performScrollToNode(hasText("Waiting for an edition", substring = true))
        list.performScrollToIndex(0)

        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove Example?").assertExists()
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        // Compose only recomposes for the removal when its test clock runs.
        idleUntil { compose.waitForIdle(); back }

        assertEquals(emptyList<Any>(), runBlocking { db.sources().all() })
    }

    /** A site's own number stays put when the edition setting changes, until the reader hands it back. */
    @Test
    fun aSiteCanHaveItsOwnNumberOfArticles() {
        val id = runBlocking { repo.addFeed("https://example.com/feed", "Example") }
        val defaultMax = MutableStateFlow(2)
        val vm = SourceDetailViewModel(repo, id, defaultMax)
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("2 articles from this site, then more if there's room") }

        compose.onNodeWithContentDescription("More articles from this site").performClick()
        compose.onNodeWithContentDescription("More articles from this site").performClick()
        idleUntil { compose.waitForIdle(); visible("At most 4 articles from this site") }
        compose.onNodeWithText("Your edition setting: 2 articles", substring = true).assertExists()

        defaultMax.value = 1
        idleUntil { compose.waitForIdle(); visible("Your edition setting: 1 article") }
        assertEquals(4, runBlocking { db.sources().byId(id)!!.maxArticles })

        compose.onNodeWithText("Use your edition setting").performClick()
        idleUntil { compose.waitForIdle(); visible("1 article from this site, then more if there's room") }
        assertNull(runBlocking { db.sources().byId(id)!!.maxArticles })
    }

    /** The button says what it sets: the reader's choice, or Automatic when the check decides. */
    @Test
    fun theArticleTextButtonNamesTheSetting() {
        val id = runBlocking { repo.addFeed("https://example.com/feed", "Example") }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article text: Automatic") }

        runBlocking { repo.chooseContentMode(id, ContentMode.PAGE) }

        idleUntil { compose.waitForIdle(); visible("Article text: Full page") }
    }

    /** At the default of 1, the edition's number is soft; a noisy site can still be held to 1 for good. */
    @Test
    fun aSiteCanBeHeldToOneArticleEvenWhenTheEditionAllowsOne() {
        val id = runBlocking { repo.addFeed("https://example.com/feed", "Example") }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("1 article from this site, then more if there's room") }

        compose.onNodeWithContentDescription("Fewer articles from this site").performClick()

        idleUntil { compose.waitForIdle(); visible("At most 1 article from this site") }
        assertEquals(1, runBlocking { db.sources().byId(id)!!.maxArticles })
    }

    @Test
    fun aTtrssAccountCanTakeOneCategoryAndLeaveArticlesUnread() {
        val http = FakeHttp()
        val server = FakeTtrss(http)
        server.categories[5] = "Tech"
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val ttrss = TtrssRepository(db, http, accounts, repo)
        val id = runBlocking {
            assertNull(ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
            db.sources().ofKind(SourceKind.TTRSS).single().id
        }
        var syncs = 0
        val vm = SourceDetailViewModel(repo, id, flowOf(1), ttrss) { syncs++ }
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("All your unread articles") }
        compose.onNodeWithText("from this site", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Change").performClick()
        idleUntil { compose.waitForIdle(); visible("Tech") }
        compose.onNodeWithText("Special").assertDoesNotExist()
        compose.onNodeWithText("Tech").performClick()
        idleUntil { compose.waitForIdle(); syncs == 1 && visible("Tech") }
        assertEquals(5, runBlocking { db.sources().byId(id)!!.ttrssCategoryId })

        compose.onNodeWithText("Mark as read in tt-rss").performClick()
        idleUntil { compose.waitForIdle(); visible("left unread in tt-rss") }
        assertEquals(false, runBlocking { db.sources().byId(id)!!.markReadOnServer })
    }

    /** Three articles: waiting, delivered and in an unsent edition. Returns the source and article ids by guid. */
    private fun sourceWithArticles(): Pair<Long, Map<String, Long>> = runBlocking {
        val id = repo.addFeed("https://example.com/feed", "Example")
        db.articles().insertNew(
            listOf("waiting" to 3L, "delivered" to 2L, "unsent" to 1L).map { (guid, age) ->
                ArticleEntity(sourceId = id, guid = guid, url = "https://example.com/$guid", title = "Article $guid", discoveredAt = Instant.now().minus(Duration.ofHours(age)))
            },
        )
        val ids = db.articles().allForSource(id).associate { it.guid to it.id }
        db.articles().setDelivered(listOf(ids.getValue("delivered")))
        db.articles().setState(listOf(ids.getValue("unsent")), ArticleState.IN_EDITION)
        id to ids
    }

    private fun show(sourceId: Long): SourceDetailViewModel {
        val vm = SourceDetailViewModel(repo, sourceId, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article waiting") }
        return vm
    }

    private fun star(title: String) = compose.onNodeWithContentDescription("Put $title in your next edition")

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun eachRowOffersTheStarAndOnlyWaitingRowsOfferMarkAsRead() {
        val (id, ids) = sourceWithArticles()
        show(id)

        star("Article waiting").assertIsOff()
        star("Article delivered").assertIsOff()
        star("Article unsent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Article waiting as read").assertExists()
        compose.onNodeWithContentDescription("Mark Article delivered as read").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Article unsent as read").assertDoesNotExist()

        star("Article delivered").performClick()
        idleUntil { compose.waitForIdle(); visible("Starred for your next edition") }
        star("Article delivered").assertIsOn()
        val delivered = runBlocking { db.articles().byId(ids.getValue("delivered"))!! }
        assertEquals("a star is a flag, not a state", ArticleState.DELIVERED, delivered.state)
        assertTrue(runBlocking { db.articles().candidates() }.any { it.id == delivered.id })
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun tappingARowOpensTheOriginalAndChangesNothing() {
        val (id, ids) = sourceWithArticles()
        show(id)

        compose.onNodeWithText("Article waiting").performClick()

        val opened = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/waiting", opened.dataString)
        assertEquals(ArticleState.NEW, runBlocking { db.articles().byId(ids.getValue("waiting"))!!.state })
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun markAsReadStaysInPlaceAndUndoBringsBackItsStateAndStar() {
        val (id, ids) = sourceWithArticles()
        val waiting = ids.getValue("waiting")
        runBlocking { repo.setStarred(waiting, true) }
        val starredAt = runBlocking { db.articles().byId(waiting)!!.starredAt }
        show(id)
        val top = compose.onNodeWithText("Article waiting").fetchSemanticsNode().boundsInRoot.top

        compose.onNodeWithContentDescription("Mark Article waiting as read").performClick()

        idleUntil { compose.waitForIdle(); visible("Undo") }
        assertEquals("the row and the snackbar", 2, compose.onAllNodes(hasText("Marked as read", substring = true)).fetchSemanticsNodes().size)
        assertEquals("the row doesn't move", top, compose.onNodeWithText("Article waiting").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(ArticleState.SKIPPED, runBlocking { db.articles().byId(waiting)!!.state })
        assertNull("marking read clears the star", runBlocking { db.articles().byId(waiting)!!.starredAt })
        compose.onNodeWithContentDescription("Mark Article waiting as read").assertDoesNotExist()
        star("Article waiting").assertIsOff()

        compose.onNodeWithText("Undo").performClick()

        idleUntil { compose.waitForIdle(); runBlocking { db.articles().byId(waiting)!!.state } == ArticleState.NEW }
        assertEquals(starredAt, runBlocking { db.articles().byId(waiting)!!.starredAt })
        idleUntil { compose.waitForIdle(); visible("Starred for your next edition") }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun whileAnEditionIsBeingMadeMarkAsReadAndUnstarringWaitAndSaySo() {
        val (id, ids) = sourceWithArticles()
        runBlocking {
            repo.setStarred(ids.getValue("delivered"), true)
            db.editions().insert(EditionEntity(title = "Being made", createdAt = Instant.now()))
        }
        show(id)

        idleUntil { compose.waitForIdle(); visible(BUILDING_NOTE) }
        compose.onNodeWithContentDescription("Mark Article waiting as read").assertIsNotEnabled()
        star("Article delivered").assertIsNotEnabled()
        star("Article waiting").assertIsEnabled().performClick()
        idleUntil { runBlocking { db.articles().byId(ids.getValue("waiting"))!!.starredAt } != null }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun anUndoOfferEndsWithTheScreenAndMarkingTheSameArticleAgainOffersItAgain() {
        val (id, ids) = sourceWithArticles()
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article waiting") }

        compose.onNodeWithContentDescription("Mark Article waiting as read").performClick()
        idleUntil { compose.waitForIdle(); visible("Undo") }
        shown = false
        idleUntil { compose.waitForIdle(); vm.undoOffer.value == null }

        shown = true
        compose.waitForIdle()
        assertFalse("no stale Undo when coming back", visible("Undo"))
        runBlocking { repo.undoMarkRead(MarkedRead(ids.getValue("waiting"), null)) }
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasContentDescription("Mark Article waiting as read")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Mark Article waiting as read").performClick()
        idleUntil { compose.waitForIdle(); visible("Undo") }
    }

    @Test
    fun articlesAreDatedByWhenTheyWerePublished() {
        val (id, _) = sourceWithArticles()
        runBlocking {
            db.articles().insertNew(
                listOf(ArticleEntity(sourceId = id, guid = "old", url = "https://example.com/old", title = "An old essay", published = Instant.parse("2023-03-03T12:00:00Z"), discoveredAt = Instant.now())),
            )
        }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("An old essay") }
        assertTrue(visible("Mar 3, 2023"))
    }

    @Test
    fun failuresAreOnlyCalledOutOnceTheyOutlastTheDay() {
        val now = Instant.parse("2026-09-29T10:00:00Z")
        val utc = ZoneOffset.UTC
        assertNull(failingLine(Instant.parse("2026-09-29T01:00:00Z"), Locale.US, now, utc))
        assertEquals("Failing since yesterday", failingLine(Instant.parse("2026-09-28T23:00:00Z"), Locale.US, now, utc))
        assertEquals("Failing for 3 days, since Sep 26", failingLine(Instant.parse("2026-09-26T08:00:00Z"), Locale.US, now, utc))
        val evening = Instant.parse("2026-09-29T18:02:00Z")
        val later = Instant.parse("2026-09-29T20:00:00Z")
        assertEquals("Last checked today at 6:02 PM", lastCheckedLine(evening, Locale.US, is24Hour = false, now = later, zone = utc)?.replace('\u202f', ' '))
        assertEquals("the phone's 24-hour setting wins", "Last checked today at 18:02", lastCheckedLine(evening, Locale.US, is24Hour = true, now = later, zone = utc))
        assertEquals("Last checked Sep 27", lastCheckedLine(Instant.parse("2026-09-27T06:02:00Z"), Locale.US, is24Hour = false, now = now, zone = utc))
    }
}
