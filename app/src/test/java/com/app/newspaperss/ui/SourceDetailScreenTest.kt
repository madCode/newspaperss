package com.app.newspaperss.ui

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
        idleUntil { visible("Up to 2 articles in each edition") }

        compose.onNodeWithContentDescription("More articles from this site").performClick()
        compose.onNodeWithContentDescription("More articles from this site").performClick()
        idleUntil { compose.waitForIdle(); visible("Up to 4 articles") }
        compose.onNodeWithText("Your edition setting: up to 2").assertExists()

        defaultMax.value = 1
        idleUntil { compose.waitForIdle(); visible("Your edition setting: up to 1") }
        assertEquals(4, runBlocking { db.sources().byId(id)!!.maxArticles })

        compose.onNodeWithText("Use your edition setting").performClick()
        idleUntil { compose.waitForIdle(); visible("Up to 1 article in each edition") }
        assertNull(runBlocking { db.sources().byId(id)!!.maxArticles })
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
        compose.onNodeWithText("Up to 1 article", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Change").performClick()
        idleUntil { compose.waitForIdle(); visible("Tech") }
        compose.onNodeWithText("Special").assertDoesNotExist()
        compose.onNodeWithText("Tech").performClick()
        idleUntil { compose.waitForIdle(); syncs == 1 && visible("Tech") }
        assertEquals(5, runBlocking { db.sources().byId(id)!!.ttrssCategoryId })

        compose.onNodeWithText("Mark as read in tt-rss").performClick()
        idleUntil { compose.waitForIdle(); visible("They stay unread there") }
        assertEquals(false, runBlocking { db.sources().byId(id)!!.markReadOnServer })
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
