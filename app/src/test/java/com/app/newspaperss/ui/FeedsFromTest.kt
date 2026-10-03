package com.app.newspaperss.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AesGcmCipher
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.StoredAccount
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
import com.app.newspaperss.ui.settings.SettingsScreen
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings › Where your feeds come from, and the server setup's sign-in state on Sources. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FeedsFromTest {
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
    private val accountData by lazy { PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("ttrss.preferences_pb") } }
    private val accounts by lazy { TtrssAccountStore(accountData, testCipher()) }
    private val sources = SourceRepository(db)
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources) }
    private var syncs = 0

    @After fun stopStore() = runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin() }

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = idleUntil { compose.waitForIdle(); visible(text) }
    private fun waitGone(text: String) = idleUntil { compose.waitForIdle(); !visible(text) }

    /** Settings from its summary, as the app shows it, with Back to the summary. */
    private fun showSettings(page: SettingsPage? = null): FeedsFromViewModel {
        val settingsVm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feedsVm = FeedsFromViewModel(store, ttrss) { syncs++ }
        compose.setContent {
            var open by remember { mutableStateOf(page) }
            when (val p = open) {
                null -> SettingsScreen(settingsVm, onOpen = { open = it })
                else -> SettingsPageScreen(settingsVm, p, onBack = { open = null }, feedsFrom = feedsVm)
            }
        }
        idleUntil { settingsVm.settings.value != null }
        return feedsVm
    }

    private fun signedIn(category: Int? = null): Long = runBlocking {
        server.categories[4] = "Ideas"
        assertNull(ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
        store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        val id = db.sources().ofKind(SourceKind.TTRSS).single().id
        if (category != null) db.sources().setTtrssCategory(id, category, server.categories[category])
        id
    }

    private fun signInWith(password: String) {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput(password)
        compose.onNodeWithText("Sign in").performClick()
    }

    @Test
    fun thePhoneSetupSaysSoAndChoosingTheServerSignsInFirst() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        showSettings()
        waitFor("This phone")
        compose.onNodeWithText("Where your feeds come from").performScrollTo().performClick()
        waitFor("My own RSS server")
        compose.onNodeWithText("This phone").assertIsSelected()

        compose.onNodeWithText("My own RSS server").performClick()
        waitFor("Sign in to your tt-rss")
        assertEquals("nothing changes before signing in", FeedsFrom.PHONE, runBlocking { store.current().feedsFrom })
        signInWith("wrong")
        waitFor("didn't accept that username and password")
        assertEquals(FeedsFrom.PHONE, runBlocking { store.current().feedsFrom })

        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement(server.password)
        compose.onNodeWithText("Sign in").performClick()
        waitFor("Signed in as reader")
        assertEquals(FeedsFrom.SERVER, runBlocking { store.current().feedsFrom })
        compose.onNodeWithText("My own RSS server").assertIsSelected()
        compose.onNodeWithText("tt-rss · rss.example.com").assertExists()
        assertEquals("a sync is asked for so the articles arrive", 1, syncs)
    }

    @Test
    fun backFromSigningInLeavesThePageAsItWas() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        showSettings(SettingsPage.FEEDS)
        waitFor("My own RSS server")
        compose.onNodeWithText("My own RSS server").performClick()
        waitFor("Sign in to your tt-rss")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        waitGone("Sign in to your tt-rss")
        compose.onNodeWithText("This phone").assertIsSelected()
    }

    @Test
    fun switchingToThePhoneSaysWhatHappensAndThenRemovesTheAccount() {
        val id = signedIn()
        runBlocking {
            db.articles().insertNew(listOf(ArticleEntity(sourceId = id, guid = "ttrss:1", url = "https://news.example/1", title = "Waiting", originId = "1")))
        }
        showSettings(SettingsPage.FEEDS)
        waitFor("Signed in as reader")

        compose.onNodeWithText("This phone").performClick()
        waitFor("Fetch your feeds on this phone instead?")
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("won't come along yet", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        waitGone("Fetch your feeds on this phone instead?")
        assertEquals("cancelling changes nothing", FeedsFrom.SERVER, runBlocking { store.current().feedsFrom })
        assertEquals(1, runBlocking { db.sources().all() }.size)

        compose.onNodeWithText("This phone").performClick()
        waitFor("Switch to this phone")
        compose.onNodeWithText("Switch to this phone").performClick()
        idleUntil { runBlocking { store.current().feedsFrom } == FeedsFrom.PHONE }
        assertTrue(runBlocking { db.sources().all() }.isEmpty())
        assertTrue("its articles go with it", runBlocking { db.articles().byIds(listOf(1L, 2L, 3L)) }.isEmpty())
        assertEquals(StoredAccount.None, runBlocking { accounts.load() })
        waitGone("Your tt-rss")
        compose.onNodeWithText("This phone").assertIsSelected()
    }

    @Test
    fun choosingThePhoneWithNoAccountSwitchesAtOnceAndClearsAnyLeftoverLogin() {
        runBlocking {
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
            // A sign-in cut short between saving the login and adding its source.
            accounts.save(com.app.newspaperss.data.TtrssAccount(server.apiUrl, server.user, server.password))
        }
        showSettings(SettingsPage.FEEDS)
        waitFor("Until you do")
        compose.onNodeWithText("This phone").performClick()
        idleUntil { runBlocking { store.current().feedsFrom } == FeedsFrom.PHONE }
        compose.onNode(isDialog()).assertDoesNotExist()
        idleUntil { runBlocking { accounts.load() } == StoredAccount.None }
    }

    @Test
    fun theAccountsSettingsAreOnThePage() {
        val id = signedIn()
        showSettings(SettingsPage.FEEDS)
        waitFor("All your unread articles")

        compose.onNodeWithText("Change").performScrollTo().performClick()
        waitFor("Ideas")
        compose.onNodeWithText("Special").assertDoesNotExist()
        compose.onNodeWithText("Ideas").performClick()
        idleUntil { compose.waitForIdle(); syncs == 1 && visible("Ideas") }
        assertEquals(4, runBlocking { db.sources().byId(id)!!.ttrssCategoryId })

        compose.onNodeWithText("Sync read status with tt-rss").performScrollTo().performClick()
        waitFor("keep their own read and unread")
        assertEquals(false, runBlocking { db.sources().byId(id)!!.markReadOnServer })
    }

    @Test
    fun startingFreshAsksFirstThenCatchesUpTtrss() {
        signedIn()
        showSettings(SettingsPage.FEEDS)
        waitFor("Back after a break?")

        compose.onNodeWithText("Start fresh").performScrollTo().performClick()
        waitFor("newspapeRSS can't undo this")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("backing out asks nothing of tt-rss", server.caughtUp.isEmpty())

        compose.onNodeWithText("Start fresh").performScrollTo().performClick()
        waitFor("newspapeRSS can't undo this")
        compose.onNodeWithText("Mark as read").performClick()
        waitFor("only the last two weeks unread")
        assertEquals(1, server.caughtUp.size)
        assertEquals("the next sync takes what's still unread", 1, syncs)
    }

    @Test
    fun signingInAgainAsTheSameUserKeepsTheCategoryAndFillsInTheLogin() {
        val id = signedIn(category = 4)
        showSettings(SettingsPage.FEEDS)
        waitFor("Sign in again")
        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        waitFor("Sign in to your tt-rss")
        compose.onNodeWithText("https://rss.example.com/tt-rss").assertExists()
        compose.onNodeWithText(server.user).assertExists()

        compose.onAllNodes(hasSetTextAction())[2].performTextInput(server.password)
        compose.onNodeWithText("Sign in").performClick()
        waitGone("Sign in to your tt-rss")
        assertEquals(4, runBlocking { db.sources().byId(id)!!.ttrssCategoryId })
    }

    @Test
    fun theServerSetupWithNoAccountAsksToSignInOnSettingsAndSources() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
        showSettings()
        waitFor("Not signed in. Tap to sign in.")
        compose.onNodeWithText("Where your feeds come from").performScrollTo().performClick()
        waitFor("Until you do, your paper has only what's on this phone")
        compose.onNodeWithText("Sign in").performClick()
        waitFor("Sign in to your tt-rss")
    }

    @Test
    fun sourcesSaysToSignInUntilTheAccountWorks() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
        var opened = false
        val vm = SourcesViewModel(sources, FeedFinder(http), ttrss, settings = store) {}
        compose.setContent { SourcesScreen(vm, onSignIn = { opened = true }) }
        waitFor("Sign in to your tt-rss")
        compose.onNodeWithText("Sign in").performClick()
        assertTrue(opened)

        signedIn()
        waitGone("Sign in to your tt-rss")
    }

    @Test
    fun thePhoneSetupNeverAsksToSignIn() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SourcesViewModel(sources, FeedFinder(http), ttrss, settings = store) {}
        compose.setContent { SourcesScreen(vm) }
        waitFor("No sources yet")
        compose.onNodeWithText("Sign in to your tt-rss").assertDoesNotExist()
    }

    @Test
    fun aPasswordThePhoneCantReadAnyMoreShowsOnTheRowAndThePage() {
        signedIn()
        val lostKey = TtrssAccountStore(accountData, AesGcmCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        val settingsVm = SettingsViewModel(store, TtrssRepository(db, http, lostKey, sources).observeStatus()) {}
        compose.setContent { SettingsScreen(settingsVm, onOpen = {}) }
        waitFor("Can't sign in to tt-rss. Tap to sign in again.")
        compose.onNodeWithText("Your tt-rss · rss.example.com").assertExists()
    }

    @Test
    fun aRefusedPasswordShowsOnThePage() {
        val id = signedIn()
        runBlocking { db.sources().recordFailure(id, java.time.Instant.now(), "tt-rss didn't accept that username and password.") }
        showSettings(SettingsPage.FEEDS)
        waitFor("Can't sign in: tt-rss didn't accept that username and password.")
    }
}
