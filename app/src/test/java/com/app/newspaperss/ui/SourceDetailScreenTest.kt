package com.app.newspaperss.ui

import org.junit.Assert.assertFalse
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.MarkedRead
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertHasClickAction
import android.content.Intent
import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
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
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.HELD_NOTICE
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.failingLine
import com.app.newspaperss.ui.sources.lastCheckedLine
import com.app.newspaperss.ui.sources.looksRead
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourceDetailScreenTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = SourceRepository(db)

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

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Remove source").performClick()
        compose.onNodeWithText("Remove Example?").assertExists()
        compose.onNode(hasText("Remove source") and hasAnyAncestor(isDialog())).performClick()
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
        assertEquals(4, runBlocking { db.sources().publication(id, PublicationEntity.OWN)?.maxArticles })

        compose.onNodeWithText("Use your edition setting").performClick()
        idleUntil { compose.waitForIdle(); visible("1 article from this site, then more if there's room") }
        assertNull(runBlocking { db.sources().publication(id, PublicationEntity.OWN)?.maxArticles })
    }

    /** The button says what it sets: the reader's choice, or Automatic when the check decides. */
    @Test
    fun theArticleTextButtonNamesTheSetting() {
        val id = runBlocking { repo.addFeed("https://example.com/feed", "Example") }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article text: Automatic") }

        runBlocking { repo.chooseContentMode(id, PublicationEntity.OWN, ContentMode.PAGE) }

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
        assertEquals(1, runBlocking { db.sources().publication(id, PublicationEntity.OWN)?.maxArticles })
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

        compose.onNodeWithText("Sync read status with tt-rss").performClick()
        idleUntil { compose.waitForIdle(); visible("keep their own read and unread") }
        assertEquals(false, runBlocking { db.sources().byId(id)!!.markReadOnServer })
    }

    @Test
    fun aTtrssFeedCanBeLeftOutAndBroughtBack() {
        val id = runBlocking {
            val account = repo.addTtrss("https://rss.example/api/")
            db.articles().insertNew(
                listOf("Quarterly Review" to "7", "Press Office" to "42").map { (title, feed) ->
                    ArticleEntity(sourceId = account, guid = "ttrss:$feed", url = "https://news.example/$feed", title = "From $title", originId = feed, originTitle = title)
                },
            )
            account
        }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Feeds in your paper") && visible("All 2") }

        compose.onNodeWithText("Choose").performClick()
        idleUntil { compose.waitForIdle(); visible("Press Office") }
        compose.onNodeWithText("Press Office").performClick()
        idleUntil { compose.waitForIdle(); visible("1 of 2: 1 left out") }
        assertEquals(listOf("42"), runBlocking { db.sources().allLeftOut().map { it.key } })

        compose.onNodeWithText("Press Office").performClick()
        idleUntil { compose.waitForIdle(); visible("All 2") }
        compose.onNodeWithText("Done").performClick()
        assertTrue(runBlocking { db.sources().allLeftOut().isEmpty() })
    }

    @Test
    fun startingFreshAsksFirstThenCatchesUpTtrss() {
        val http = FakeHttp()
        val server = FakeTtrss(http)
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val ttrss = TtrssRepository(db, http, accounts, repo)
        val id = runBlocking {
            assertNull(ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
            db.sources().ofKind(SourceKind.TTRSS).single().id
        }
        var syncs = 0
        val vm = SourceDetailViewModel(repo, id, flowOf(1), ttrss) { syncs++ }
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Back after a break?") }

        compose.onNodeWithText("Start fresh").performClick()
        idleUntil { compose.waitForIdle(); visible("newspapeRSS can't undo this") }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("backing out asks nothing of tt-rss", server.caughtUp.isEmpty())

        compose.onNodeWithText("Start fresh").performClick()
        idleUntil { compose.waitForIdle(); visible("newspapeRSS can't undo this") }
        compose.onNodeWithText("Mark as read").performClick()
        idleUntil { compose.waitForIdle(); visible("only the last two weeks unread") }
        assertEquals(1, server.caughtUp.size)
        assertEquals("the next sync takes what's still unread", 1, syncs)
    }

    /**
     * Waiting, delivered and in an unsent edition, plus [moreWaiting] more waiting ones ("Article
     * more1"…), newest first. Returns the source and article ids by guid.
     */
    @Test
    @Config(fontScale = 2f)
    fun atLargeTextThePerSiteButtonsGetTheirOwnLine() {
        // Beside the words, they squeezed them to a few words a line.
        val (id, _) = sourceWithArticles()
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("from this site") }
        val words = compose.onNodeWithText("from this site", substring = true).fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("More articles from this site").fetchSemanticsNode().boundsInRoot
        assertTrue("below the words", more.top >= words.bottom)
    }

    private fun sourceWithArticles(moreWaiting: Int = 0): Pair<Long, Map<String, Long>> = runBlocking {
        val id = repo.addFeed("https://example.com/feed", "Example")
        val guids = listOf("waiting", "delivered", "unsent") + (1..moreWaiting).map { "more$it" }
        db.articles().insertNew(
            guids.mapIndexed { i, guid ->
                ArticleEntity(sourceId = id, guid = guid, url = "https://example.com/$guid", title = "Article $guid", discoveredAt = Instant.now().minus(Duration.ofHours(i + 1L)))
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

    /** The row itself: its title and details, merged, and what a tap on it does. */
    private fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())

    private fun markRead(title: String) = compose.onNodeWithContentDescription("Mark $title as read")

    private fun markUnread(title: String) = compose.onNodeWithContentDescription("Mark $title as unread")

    private fun state(id: Long) = runBlocking { db.articles().byId(id)!!.state }

    private fun settle(condition: () -> Boolean) = idleUntil { compose.waitForIdle(); condition() }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun eachRowHasTheStarAndTheReadToggleExceptInAnUnsentEdition() {
        val (id, ids) = sourceWithArticles()
        show(id)

        star("Article waiting").assertIsOff()
        star("Article delivered").assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not starred"))
        star("Article unsent").assertDoesNotExist()
        markRead("Article waiting").assertHasClickAction()
        markUnread("Article delivered").assertHasClickAction()
        markRead("Article unsent").assertDoesNotExist()
        markUnread("Article unsent").assertDoesNotExist()
        compose.onNodeWithText("Tap ● to mark one read or unread, and ☆ to put it in your next edition.").assertExists()

        star("Article delivered").performClick()
        settle { visible("Starred for your next edition") }
        star("Article delivered").assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred"))
        val delivered = runBlocking { db.articles().byId(ids.getValue("delivered"))!! }
        assertEquals("a star is a flag, not a state", ArticleState.DELIVERED, delivered.state)
        assertTrue(runBlocking { db.articles().candidates() }.any { it.id == delivered.id })

        star("Article delivered").performClick()
        settle { runBlocking { db.articles().byId(ids.getValue("delivered"))!!.starredAt } == null }
    }

    /** Read rows are dimmed so what's still to come stands out; a star brings one back to full strength. */
    @Test
    fun readDeliveredAndExpiredRowsLookReadUnlessStarred() {
        fun article(state: ArticleState, starred: Boolean = false) =
            ArticleEntity(sourceId = 1, guid = "g", url = "https://example.com", title = "t", state = state, starredAt = if (starred) Instant.now() else null)
        assertEquals(
            listOf(false, false, true, true, true),
            listOf(ArticleState.NEW, ArticleState.IN_EDITION, ArticleState.SKIPPED, ArticleState.DELIVERED, ArticleState.EXPIRED).map { looksRead(article(it)) },
        )
        assertFalse("starred again, it's going out", looksRead(article(ArticleState.DELIVERED, starred = true)))
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
        assertEquals(ArticleState.NEW, state(ids.getValue("waiting")))
    }

    /** The status mark is a toggle: a second tap undoes the first, and the rows never move, so e-ink doesn't redraw the list. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun theReadToggleMarksAWaitingArticleReadAndATapBackBringsItBack() {
        val (id, ids) = sourceWithArticles()
        val waiting = ids.getValue("waiting")
        runBlocking { repo.setStarred(waiting, true) }
        show(id)
        val top = compose.onNodeWithText("Article waiting").fetchSemanticsNode().boundsInRoot.top

        markRead("Article waiting").performClick()

        settle { state(waiting) == ArticleState.SKIPPED }
        settle { visible("Marked as read") }
        assertEquals("the row doesn't move", top, compose.onNodeWithText("Article waiting").fetchSemanticsNode().boundsInRoot.top)
        assertNull("marking read clears the star", runBlocking { db.articles().byId(waiting)!!.starredAt })
        assertFalse("a second tap undoes it, so no snackbar", visible("Undo"))

        markUnread("Article waiting").performClick()

        settle { state(waiting) == ArticleState.NEW }
        settle { compose.onAllNodes(hasContentDescription("Mark Article waiting as read")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The mark is narrow so titles line up, but a tap near it still lands on it rather than opening the article. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun aTapJustBesideTheMarkStillTogglesIt() {
        val (id, ids) = sourceWithArticles()
        show(id)
        val mark = markRead("Article waiting").fetchSemanticsNode().boundsInRoot
        val nudge = with(compose.density) { 10.dp.toPx() }

        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(mark.right + nudge, mark.center.y)) }

        settle { state(ids.getValue("waiting")) == ArticleState.SKIPPED }
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
    }

    /** "Unread" on a sent article: back with the waiting ones, not ahead of them like a star. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun aDeliveredArticleMarkedUnreadWaitsAgainAndATapBackMarksItRead() {
        val (id, ids) = sourceWithArticles()
        val delivered = ids.getValue("delivered")
        show(id)

        markUnread("Article delivered").performClick()

        settle { state(delivered) == ArticleState.NEW }
        assertNull("not starred: it waits its turn", runBlocking { db.articles().byId(delivered)!!.starredAt })
        assertTrue(runBlocking { db.articles().candidates() }.any { it.id == delivered })
        settle { compose.onAllNodes(hasContentDescription("Mark Article delivered as read")).fetchSemanticsNodes().isNotEmpty() }

        markRead("Article delivered").performClick()

        settle { state(delivered) == ArticleState.SKIPPED }
    }

    /**
     * The bar counts only what an action would change: an article already delivered or in an
     * unsent edition can't be marked read. One Undo brings back the whole batch, and the rows
     * never move, so e-ink doesn't redraw the list.
     */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun holdingARowSelectsItAndMarkingSeveralReadCanBeUndoneTogether() {
        val (id, ids) = sourceWithArticles(moreWaiting = 1)
        show(id)
        val top = compose.onNodeWithText("Article delivered").fetchSemanticsNode().boundsInRoot.top

        row("Article waiting").performTouchInput { longClick() }
        settle { visible("1 selected") }
        compose.onNodeWithText("1 selected").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals("entering selection moves nothing", top, compose.onNodeWithText("Article delivered").fetchSemanticsNode().boundsInRoot.top)
        row("Article waiting").assertIsOn()
        row("Article more1").assertIsOff().performClick()
        row("Article delivered").performClick()
        row("Article unsent").performClick()
        settle { visible("4 selected") }
        assertNull("tapping selects instead of opening", shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)

        assertEquals(
            "side by side at normal size",
            compose.onNodeWithContentDescription("Put 3 articles in your next edition").fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithText("Mark 2 as read").fetchSemanticsNode().boundsInRoot.top,
        )
        compose.onNodeWithText("Mark 2 as read").assertIsEnabled().performClick()

        settle { visible("Marked 2 as read") }
        assertFalse("done selecting", visible("selected"))
        assertEquals(ArticleState.SKIPPED, state(ids.getValue("waiting")))
        assertEquals(ArticleState.SKIPPED, state(ids.getValue("more1")))
        assertEquals(ArticleState.DELIVERED, state(ids.getValue("delivered")))
        assertEquals(ArticleState.IN_EDITION, state(ids.getValue("unsent")))
        assertEquals("rows stay put", top, compose.onNodeWithText("Article delivered").fetchSemanticsNode().boundsInRoot.top)

        compose.onNodeWithText("Undo").performClick()

        settle { state(ids.getValue("waiting")) == ArticleState.NEW && state(ids.getValue("more1")) == ArticleState.NEW }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun selectStarsSeveralAtOnceAndUndoTakesThemAllOut() {
        val (id, ids) = sourceWithArticles()
        show(id)

        compose.onNodeWithText("Select").performClick()
        settle { visible("0 selected") }
        compose.onNodeWithText("Tap articles to choose them.").assertExists()
        row("Article waiting").performClick()
        row("Article delivered").performClick()
        compose.onNodeWithContentDescription("Put 2 articles in your next edition").performClick()

        settle { visible("2 in your next edition") }
        assertTrue(runBlocking { db.articles().byId(ids.getValue("waiting"))!!.starredAt } != null)
        assertTrue(runBlocking { db.articles().byId(ids.getValue("delivered"))!!.starredAt } != null)

        compose.onNodeWithText("Undo").performClick()

        settle { runBlocking { db.articles().candidates() }.none { it.starredAt != null } }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun backAndCloseLeaveSelectionWithoutChangingAnything() {
        val (id, ids) = sourceWithArticles()
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        var back: OnBackPressedDispatcher? = null
        var left = false
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SourceDetailScreen(vm, onBack = { left = true })
        }
        idleUntil { visible("Article waiting") }

        row("Article waiting").performTouchInput { longClick() }
        settle { visible("1 selected") }
        back!!.onBackPressed()
        settle { !visible("selected") }
        compose.onNodeWithText("Select").performClick()
        row("Article waiting").performClick()
        settle { visible("1 selected") }
        compose.onNodeWithContentDescription("Stop selecting").performClick()
        settle { !visible("selected") }

        assertFalse("back left selection, not the screen", left)
        assertEquals(ArticleState.NEW, state(ids.getValue("waiting")))
        compose.onNodeWithText("Select").performClick()
        settle { visible("0 selected") }
        row("Article waiting").assertIsOff()
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun theSelectionSurvivesTheScreenBeingRecreated() {
        val (id, _) = sourceWithArticles(moreWaiting = 1)
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article waiting") }
        row("Article waiting").performTouchInput { longClick() }
        row("Article more1").performClick()
        settle { visible("2 selected") }

        restoration.emulateSavedInstanceStateRestore()

        settle { visible("2 selected") }
        row("Article waiting").assertIsOn()
        row("Article more1").assertIsOn()
        row("Article delivered").assertIsOff()
        compose.onNodeWithText("Mark 2 as read").assertExists()
    }

    /** The counts follow the articles as they are now, not as they were when selected. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun aSelectedArticleThatGoesIntoAnEditionMeanwhileIsNoLongerCounted() {
        val (id, ids) = sourceWithArticles(moreWaiting = 1)
        show(id)
        row("Article waiting").performTouchInput { longClick() }
        row("Article more1").performClick()
        settle { visible("Mark 2 as read") }

        runBlocking { db.articles().setState(listOf(ids.getValue("more1")), ArticleState.IN_EDITION) }

        settle { visible("Mark 1 as read") }
        compose.onNodeWithText("Mark 1 as read").performClick()
        settle { visible("Marked as read") }
        assertEquals(ArticleState.IN_EDITION, state(ids.getValue("more1")))
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

        settle { visible(BUILDING_NOTE) }
        markRead("Article waiting").performClick()
        settle { visible(HELD_NOTICE) }
        assertEquals("held, and the tap doesn't open the article instead", ArticleState.NEW, state(ids.getValue("waiting")))
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        star("Article delivered").assertIsNotEnabled()
        row("Article waiting").performTouchInput { longClick() }
        row("Article delivered").performClick()
        settle { visible("2 selected") }
        compose.onNodeWithText("Mark 1 as read").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Put 1 article in your next edition").assertIsEnabled()
        compose.onNodeWithContentDescription("Stop selecting").performClick()
        star("Article waiting").assertIsEnabled().performClick()
        idleUntil { runBlocking { db.articles().byId(ids.getValue("waiting"))!!.starredAt } != null }
    }

    /** A build that starts between the tap and the write holds the whole batch, and says so. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun aBatchCaughtByABuildStartingChangesNothingAndSaysWhy() {
        val (id, ids) = sourceWithArticles()
        val vm = show(id)
        runBlocking { db.editions().insert(EditionEntity(title = "Being made", createdAt = Instant.now())) }

        vm.markRead(listOf(ids.getValue("waiting")))

        settle { visible("Your edition is being made, so nothing was changed") }
        assertEquals(ArticleState.NEW, state(ids.getValue("waiting")))
    }

    /**
     * At the end of a scrolled list, neither the bar appearing nor it going away after an action
     * may move the rows: on e-ink that would redraw the whole list.
     */
    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun atTheEndOfTheListSelectingAndActingMoveNoRows() {
        val (id, ids) = sourceWithArticles(moreWaiting = 8)
        show(id)
        compose.onNode(hasScrollAction()).performScrollToIndex(11)
        compose.waitForIdle()
        fun top() = compose.onNodeWithText("Article more5").fetchSemanticsNode().boundsInRoot.top
        val before = top()

        row("Article more5").performTouchInput { longClick() }
        row("Article more6").performClick()
        settle { visible("Mark 2 as read") }
        assertEquals("the bar lies over the list", before, top())

        compose.onNodeWithText("Mark 2 as read").performClick()
        settle { visible("Marked 2 as read") && !visible("selected") }
        assertEquals("the bar going doesn't scroll the list", before, top())
        assertEquals(ArticleState.SKIPPED, state(ids.getValue("more5")))
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun takingSeveralOutOfTheNextEditionIsOneUndoAndWaitsForABuild() {
        val (id, ids) = sourceWithArticles()
        val starred = runBlocking {
            repo.setStarred(ids.getValue("waiting"), true)
            repo.setStarred(ids.getValue("delivered"), true)
            db.articles().byIds(ids.values).associate { it.id to it.starredAt }
        }
        show(id)
        compose.onNodeWithText("Select").performClick()
        row("Article waiting").performClick()
        row("Article delivered").performClick()
        val takeOut = compose.onNodeWithContentDescription("Take 2 articles out of your next edition")
        takeOut.assertIsEnabled()

        val build = runBlocking { db.editions().insert(EditionEntity(title = "Being made", createdAt = Instant.now())) }
        settle { visible(BUILDING_NOTE) }
        takeOut.assertIsNotEnabled()
        runBlocking { db.editions().update(db.editions().byId(build)!!.copy(status = EditionStatus.FAILED)) }
        settle { !visible(BUILDING_NOTE) }

        takeOut.assertIsEnabled().performClick()
        settle { visible("2 taken out of your next edition") }
        assertTrue(runBlocking { db.articles().byIds(ids.values) }.all { it.starredAt == null })

        compose.onNodeWithText("Undo").performClick()
        settle { runBlocking { db.articles().byIds(ids.values) }.associate { it.id to it.starredAt } == starred }
    }

    /** An Undo that comes too late, once a build holds the change, says so instead of failing quietly. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun anUndoThatABuildHoldsBackSaysSo() {
        val (id, ids) = sourceWithArticles()
        show(id)
        compose.onNodeWithText("Select").performClick()
        row("Article waiting").performClick()
        row("Article delivered").performClick()
        compose.onNodeWithContentDescription("Put 2 articles in your next edition").performClick()
        settle { visible("2 in your next edition") }

        runBlocking { db.editions().insert(EditionEntity(title = "Being made", createdAt = Instant.now())) }
        compose.onNodeWithText("Undo").performClick()

        settle { visible("2 articles couldn't be changed back") }
        assertTrue("still starred, as the build may have them", runBlocking { db.articles().byIds(ids.values) }.count { it.starredAt != null } == 2)
    }

    /** At 200% the two labelled actions stack at full width rather than squeezing their words. */
    @Test
    // Native graphics: the default fake text measurement would make any label fit.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h1600dp", fontScale = 2f)
    fun atLargeFontSizesTheBarsButtonsStack() {
        val (id, _) = sourceWithArticles()
        show(id)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Article waiting") and hasClickAction())
        row("Article waiting").performTouchInput { longClick() }
        row("Article delivered").performClick()
        settle { visible("Mark 1 as read") }

        val star = compose.onNodeWithContentDescription("Put 2 articles in your next edition").fetchSemanticsNode().boundsInRoot
        val mark = compose.onNodeWithText("Mark 1 as read").fetchSemanticsNode().boundsInRoot
        assertTrue("stacked", mark.top >= star.bottom)
        assertEquals("full width", star.width, mark.width, 1f)
    }

    /** Selecting while an Undo is showing mustn't put the bar's buttons under the snackbar. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun theSnackbarSitsAboveTheSelectionBar() {
        val (id, ids) = sourceWithArticles(moreWaiting = 1)
        show(id)
        row("Article waiting").performTouchInput { longClick() }
        compose.onNodeWithText("Mark 1 as read").performClick()
        settle { visible("Undo") }

        row("Article more1").performTouchInput { longClick() }
        settle { visible("Mark 1 as read") }

        val undo = compose.onNodeWithText("Undo").fetchSemanticsNode().boundsInRoot
        val mark = compose.onNodeWithText("Mark 1 as read").fetchSemanticsNode().boundsInRoot
        assertTrue("snackbar above the bar", undo.bottom <= mark.top)
        compose.onNodeWithText("Mark 1 as read").performClick()
        settle { state(ids.getValue("more1")) == ArticleState.SKIPPED }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun anUndoOfferEndsWithTheScreenAndMarkingTheSameArticleAgainOffersItAgain() {
        val (id, ids) = sourceWithArticles()
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SourceDetailScreen(vm, onBack = {}) }
        idleUntil { visible("Article waiting") }

        row("Article waiting").performTouchInput { longClick() }
        compose.onNodeWithText("Mark 1 as read").performClick()
        settle { visible("Undo") }
        shown = false
        settle { vm.undoOffer.value == null }

        shown = true
        compose.waitForIdle()
        assertFalse("no stale Undo when coming back", visible("Undo"))
        runBlocking { repo.undoMarkRead(MarkedRead(ids.getValue("waiting"), null)) }
        settle { state(ids.getValue("waiting")) == ArticleState.NEW }
        row("Article waiting").performTouchInput { longClick() }
        compose.onNodeWithText("Mark 1 as read").performClick()
        settle { visible("Undo") }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun articlesAreDatedByWhenTheyWerePublished() {
        val id = runBlocking { repo.addFeed("https://example.com/feed", "Example") }
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
    fun aDateFromTheFutureIsAFeedsMistakeAndRowsGoByTheDateShown() = runBlocking {
        val id = repo.addFeed("https://example.com/feed", "Example")
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        db.articles().insertNew(
            listOf(
                // Fetched together; one feed lists them oldest first.
                ArticleEntity(sourceId = id, guid = "a", url = "https://example.com/a", title = "Older", published = now.minus(Duration.ofDays(9)), discoveredAt = now),
                ArticleEntity(sourceId = id, guid = "b", url = "https://example.com/b", title = "Newer", published = now.minus(Duration.ofDays(2)), discoveredAt = now),
                ArticleEntity(sourceId = id, guid = "c", url = "https://example.com/c", title = "Scheduled", published = Instant.parse("2099-12-31T00:00:00Z"), discoveredAt = now.minus(Duration.ofDays(1))),
            ),
        )
        assertEquals(listOf("Scheduled", "Newer", "Older"), repo.observeRecentArticles(id).first().map { it.title })
        assertEquals(now.minus(Duration.ofDays(1)), repo.observeRecentArticles(id).first().single { it.title == "Scheduled" }.shownDate)
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
