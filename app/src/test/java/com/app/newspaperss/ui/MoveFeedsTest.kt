package com.app.newspaperss.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.settings.FeedsFromViewModel
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Moving phone feeds into tt-rss from the screens: Sources' banner and sheet, and the offer after sign-in. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MoveFeedsTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private val server = FakeTtrss(http)
    // Cancelled before TemporaryFolder deletes the files: a write still running after that fails
    // its rename, and the error lands in whichever test is running then.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }) }
    private val accounts by lazy { TtrssAccountStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("ttrss.preferences_pb") }, testCipher()) }
    private val sources = SourceRepository(db)
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources) }
    private var scheduled = 0
    private val moves by lazy { FeedMoves(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("m.preferences_pb") }, db, ttrss) { scheduled++ } }

    @After fun stopStore() = runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin() }

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = idleUntil { compose.waitForIdle(); visible(text) }
    private fun waitGone(text: String) = idleUntil { compose.waitForIdle(); !visible(text) }

    private fun signedIn() = runBlocking {
        server.categories[4] = "Science"
        assertTrue(ttrss.connect("rss.example.com/tt-rss", server.user, server.password) == null)
        store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
    }

    private fun phoneFeed(url: String, title: String) = runBlocking { sources.addFeed(url, title) }

    private fun showSources(): SourcesViewModel {
        val vm = SourcesViewModel(sources, FeedFinder(http), ttrss, settings = store, moves = moves) {}
        compose.setContent { SourcesScreen(vm) }
        idleUntil { vm.screen.value != null }
        return vm
    }

    @Test
    fun theBannerShowsOnlyWithAServerItCanMoveToAndAPhoneFeedToMove() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        phoneFeed("https://aeon.example/feed", "Aeon")
        runBlocking { sources.addList(CuratedLists.all.first()) }
        val vm = showSources()
        waitFor("Aeon")
        assertFalse("not in the phone setup", visible("fetched by this phone"))

        signedIn()
        waitFor("1 feed is fetched by this phone")

        runBlocking { db.sources().delete(db.sources().all().single { it.title == "Aeon" }) }
        waitGone("fetched by this phone")
        assertFalse("with nothing to move, no group for it", visible("Still on this phone"))
        assertTrue("a curated list stays on the phone in both setups", visible(CuratedLists.all.first().title))

        phoneFeed("https://aeon.example/feed", "Aeon")
        waitFor("1 feed is fetched by this phone")
        runBlocking { accounts.clear() }
        idleUntil { vm.screen.value?.needsSignIn == true }
        compose.waitForIdle()
        assertFalse("nowhere to move them without a working account", visible("fetched by this phone"))
    }

    @Test
    fun theSheetMovesTheFeedsAndSourcesSaysSo() {
        signedIn()
        server.feeds[7] = FakeTtrss.Feed("Morning Wire", "https://wire.example/rss")
        runBlocking { ttrss.listFor(ttrss.login()!!) }
        phoneFeed("https://aeon.example/feed", "Aeon")
        phoneFeed("https://wire.example/rss", "Morning Wire (phone)")
        showSources()
        waitFor("2 feeds are fetched by this phone")
        assertTrue(visible("Still on this phone"))

        compose.onNodeWithText("Move them to tt-rss").performClick()
        waitFor("Move 2 feeds to tt-rss")
        compose.onNodeWithText("Already in your tt-rss: just removed here").assertExists()
        waitFor("Category for the new ones")
        idleUntil { compose.waitForIdle(); visible("Uncategorized") }
        compose.onNodeWithText("Move 2").performClick()
        idleUntil { runBlocking { moves.current().running } }
        assertEquals(1, scheduled)
        waitFor("Moving 1 of 2 · Aeon")

        runBlocking { moves.run() }
        waitFor("Moved 2 feeds to your tt-rss")
        assertFalse(visible("fetched by this phone"))
        assertFalse(visible("Morning Wire (phone)"))
        assertFalse("once they're moved, the group goes", visible("Still on this phone"))
    }

    @Test
    fun aPartialMoveSaysWhyAndMovesOnlyTheOthersAgain() {
        signedIn()
        phoneFeed("https://aeon.example/feed", "Aeon")
        phoneFeed("https://blocked.example/feed", "Blocked")
        server.refuse["https://blocked.example/feed"] = 5
        runBlocking {
            moves.start(db.sources().all().filter { it.kind == SourceKind.FEED }.map { it.id }, TtrssCategory(0, "Uncategorized"))
            moves.run()
        }
        showSources()
        waitFor("Moved 1 of 2.")
        compose.onNodeWithText("Blocked: tt-rss couldn't download it.").assertExists()

        compose.onNodeWithText("Move the other one").performClick()
        waitFor("Move 1 feed to tt-rss")
        idleUntil { compose.waitForIdle(); visible("Uncategorized") }
        server.refuse.clear()
        compose.onNodeWithText("Move 1").performClick()
        idleUntil { runBlocking { moves.current().running } }
        runBlocking { moves.run() }
        waitFor("Moved 1 feed to your tt-rss")
        assertEquals(listOf("https://aeon.example/feed", "https://blocked.example/feed", "https://blocked.example/feed"), server.subscribed.map { it.first })
    }

    private fun showFeedsFrom(): FeedsFromViewModel {
        val settingsVm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feedsVm = FeedsFromViewModel(store, ttrss, moves, sources)
        compose.setContent { SettingsPageScreen(settingsVm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feedsVm) }
        idleUntil { settingsVm.settings.value != null }
        return feedsVm
    }

    private fun signInFromTheForm() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput(server.password)
        compose.onNodeWithText("Sign in").performClick()
    }

    @Test
    fun signingInFromThePhoneSetupOffersTheMoveAtOnce() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        server.feeds[7] = FakeTtrss.Feed("Morning Wire", "https://wire.example/rss", categoryId = 4)
        server.categories[4] = "Science"
        phoneFeed("https://aeon.example/feed", "Aeon")
        phoneFeed("https://quanta.example/feed", "Quanta")
        showFeedsFrom()
        waitFor("On my own RSS server")
        compose.onNodeWithText("On my own RSS server").performClick()
        waitFor("Sign in to your tt-rss")
        signInFromTheForm()

        waitFor("Move your 2 phone feeds to tt-rss?")
        compose.onNodeWithText("Signed in. 1 feed in 1 category.").assertExists()
        compose.onNodeWithText("Not now").performScrollTo().performClick()
        waitGone("Move your 2 phone feeds")

        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        waitFor("Sign in to your tt-rss")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput(server.password)
        compose.onNodeWithText("Sign in").performClick()
        waitFor("Signed in as reader")
        assertFalse("signing in again isn't the moment to ask", visible("Move your 2 phone feeds"))
    }

    @Test
    fun theOffersMoveOpensTheSameSheet() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        phoneFeed("https://aeon.example/feed", "Aeon")
        showFeedsFrom()
        waitFor("On my own RSS server")
        compose.onNodeWithText("On my own RSS server").performClick()
        waitFor("Sign in to your tt-rss")
        signInFromTheForm()

        waitFor("Move your 1 phone feed to tt-rss?")
        compose.onNodeWithText("Signed in.").assertExists()
        compose.onNodeWithText("Move 1").performScrollTo().performClick()
        waitFor("Move 1 feed to tt-rss")
        idleUntil { compose.waitForIdle(); visible("Uncategorized") }
        compose.onNodeWithText("Move 1").performClick()
        idleUntil { runBlocking { moves.current().running } }
        waitFor("Moving 1 of 1 · Aeon")
    }

    @Test
    fun signingInAsSomeoneElseGivesBackFeedsMovedIntoTheLastAccount() {
        signedIn()
        val aeon = phoneFeed("https://aeon.example/feed", "Aeon")
        runBlocking {
            db.articles().insertIgnoring(com.app.newspaperss.data.ArticleEntity(sourceId = aeon, guid = "s", url = "https://aeon.example/s", title = "Starred", starredAt = java.time.Instant.now()))
            moves.start(listOf(aeon), TtrssCategory(0, "Uncategorized"))
            moves.run()
        }
        assertTrue(runBlocking { db.sources().byId(aeon)!!.paused })
        val vm = showFeedsFrom()
        server.user = "partner"
        server.password = "theirs"
        idleUntil { vm.state.value?.ttrss?.source != null }
        vm.openSignIn()
        vm.editSignIn(vm.form.value!!.copy(user = "partner", password = "theirs"))
        vm.signIn()

        // A phone feed again, which the banner offers to move into this account.
        idleUntil { runBlocking { !db.sources().byId(aeon)!!.paused && moves.current().retiring.isEmpty() } }
    }
}
