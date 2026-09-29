package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private var syncRequests = 0

    @Before
    fun show() {
        val sources = SourceRepository(db)
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val vm = SourcesViewModel(sources, FeedFinder(http), TtrssRepository(db, http, accounts, sources)) { syncRequests++ }
        compose.setContent { SourcesScreen(vm) }
        // Room delivers on its own executor, which Compose's idling doesn't track.
        waitFor("No sources yet")
    }

    private fun waitFor(text: String, present: Boolean = true) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() == present
    }

    @After fun close() = db.close()

    private fun addSource(input: String) {
        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(input)
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
    }

    @Test
    fun aTtrssAccountIsAddedFromTheMenuAndAWrongPasswordIsExplained() {
        val server = FakeTtrss(http)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add tt-rss account").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput("wrong")
        compose.onNodeWithText("Test and add").performClick()
        waitFor("didn't accept that username and password")

        compose.onAllNodes(hasSetTextAction())[2].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[2].performTextInput(server.password)
        compose.onNodeWithText("Test and add").performClick()
        waitFor("Test and add", present = false)
        waitFor(SourceRepository.TTRSS_TITLE)
        compose.onNodeWithText("rss.example.com").assertIsDisplayed()
        assertEquals(1, syncRequests)
    }

    @Test
    fun addingASiteFindsItsFeedAndStartsASync() {
        http.page("https://example.com", "<html><head><link rel=alternate type=application/rss+xml href=/feed title=Posts></head></html>")
        http.page("https://example.com/feed", rss("Example"))

        addSource("example.com")

        waitFor("Posts")
        compose.onNodeWithText("Posts").assertIsDisplayed()
        assertEquals(1, syncRequests)
    }

    @Test
    fun aSiteWithSeveralFeedsAsksWhichOne() {
        http.page(
            "https://example.com",
            """<html><head>
                 <link rel=alternate type=application/rss+xml href=/news title=News>
                 <link rel=alternate type=application/atom+xml href=/essays title=Essays>
               </head></html>""",
        )
        addSource("example.com")
        waitFor("Which part of this site?")
        compose.onNodeWithText("Essays").performClick()
        waitFor("Which part of this site?", present = false)
        compose.onNodeWithText("Essays").assertIsDisplayed()
    }

    @Test
    fun aSiteWithoutAFeedExplainsWhy() {
        http.page("https://example.com", "<html></html>")
        addSource("example.com")
        waitFor("No feed found")
    }
}
