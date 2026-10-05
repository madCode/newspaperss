package com.app.newspaperss.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Today's card shows the latest edition's opening: a lead, the next two in order, and how many more. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class TodayFrontPageTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()

    private fun today(status: EditionStatus, starred: Int? = null) {
        val vm = TodayViewModel(EditionRepository(db, tmp.newFolder()), flowOf(null)) {}
        runBlocking {
            val id = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = status, articleCount = 5, minutes = 20.0))
            db.editions().insertArticles(
                (0 until 5).map { EditionArticleEntity(editionId = id, articleId = null, position = it, title = "Story $it", sourceTitle = "Source $it", minutes = 4.0, starred = it == starred) },
            )
        }
        compose.setContent { TodayScreen(vm) }
        idleUntil { compose.waitForIdle(); vm.state.value.editions?.isNotEmpty() == true }
    }

    @Test
    fun aReadyEditionLeadsWithTheStarThenTheFirstTwoInOrder() {
        today(EditionStatus.READY, starred = 3)

        // TalkBack hears the reason without the star glyph.
        compose.onNodeWithContentDescription("You starred this · Source 3 · 4 min", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Story 3").assertExists()
        compose.onNodeWithText("Story 0").assertExists()
        compose.onNodeWithText("Story 1").assertExists()
        compose.onNodeWithText("Story 2").assertDoesNotExist()
        compose.onNodeWithText("and 2 more").assertExists()
    }

    @Test
    fun aFailedEditionDoesNotPreviewArticlesThatWentBackToWait() {
        today(EditionStatus.FAILED)

        compose.onNodeWithText("Story 0").assertDoesNotExist()
        compose.onNodeWithText("and 2 more").assertDoesNotExist()
    }
}
