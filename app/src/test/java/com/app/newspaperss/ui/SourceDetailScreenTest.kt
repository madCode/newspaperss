package com.app.newspaperss.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlinx.coroutines.runBlocking
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
        val vm = SourceDetailViewModel(repo, id)
        compose.setContent { SourceDetailScreen(vm, onBack = { back = true }) }
        idleUntil { visible("Couldn't reach the site.") }

        compose.onNodeWithText("Failing for 3 days", substring = true).assertExists()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Went out"))
        compose.onNodeWithText("Delivered", substring = true).assertExists()
        list.performScrollToNode(hasText("Waiting for an edition", substring = true))
        list.performScrollToIndex(0)

        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove Example?").assertExists()
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        idleUntil { back }

        assertEquals(emptyList<Any>(), runBlocking { db.sources().all() })
    }

    @Test
    fun failuresAreOnlyCalledOutOnceTheyOutlastTheDay() {
        val now = Instant.parse("2026-09-29T10:00:00Z")
        val utc = ZoneOffset.UTC
        assertNull(failingLine(Instant.parse("2026-09-29T01:00:00Z"), Locale.US, now, utc))
        assertEquals("Failing since yesterday", failingLine(Instant.parse("2026-09-28T23:00:00Z"), Locale.US, now, utc))
        assertEquals("Failing for 3 days, since Sep 26", failingLine(Instant.parse("2026-09-26T08:00:00Z"), Locale.US, now, utc))
        assertEquals("Last checked today at 6:02 AM", lastCheckedLine(Instant.parse("2026-09-29T06:02:00Z"), Locale.US, now, utc)?.replace(' ', ' '))
        assertEquals("Last checked Sep 27", lastCheckedLine(Instant.parse("2026-09-27T06:02:00Z"), Locale.US, now, utc))
    }
}
