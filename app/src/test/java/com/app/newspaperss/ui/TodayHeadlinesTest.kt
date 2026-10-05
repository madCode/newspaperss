package com.app.newspaperss.ui

import android.app.Application
import androidx.compose.ui.test.assertHasNoClickAction
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

/** Today's card shows the latest edition's first headlines, in the book's order, and how many more. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class TodayHeadlinesTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()

    private fun today(status: EditionStatus, starred: Int? = null, count: Int = 5) {
        val vm = TodayViewModel(EditionRepository(db, tmp.newFolder()), flowOf(null)) {}
        runBlocking {
            val id = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = status, articleCount = count, minutes = 4.0 * count))
            // Last first, so the card's order can only come from the positions.
            db.editions().insertArticles(
                (count - 1 downTo 0).map { EditionArticleEntity(editionId = id, articleId = null, position = it, title = "Story $it", sourceTitle = "Source $it", minutes = 4.0, starred = it == starred) },
            )
        }
        compose.setContent { TodayScreen(vm) }
        idleUntil { compose.waitForIdle(); vm.state.value.editions?.isNotEmpty() == true }
    }

    @Test
    fun aReadyEditionShowsItsFirstThreeInOrderAndMarksTheStar() {
        today(EditionStatus.READY, starred = 1)

        compose.onNodeWithText("Story 0").assertExists()
        compose.onNodeWithText("Story 1").assertExists()
        compose.onNodeWithText("Story 2").assertExists()
        compose.onNodeWithText("Story 3").assertDoesNotExist()
        compose.onNodeWithText("and 2 more").assertExists()
        // TalkBack hears the star in words.
        compose.onNodeWithContentDescription("Starred, Source 1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aShortEditionShowsEveryHeadlineAndNoMore() {
        today(EditionStatus.READY, count = 3)

        compose.onNodeWithText("Story 2").assertExists()
        compose.onNodeWithText("more", substring = true).assertDoesNotExist()
    }

    @Test
    fun whatCountsAsSentIsItsOwnTalkBackStopNotPartOfTheCard() {
        today(EditionStatus.READY)

        // Merged into the card, TalkBack would read it before Send instead of between Send and I've sent it.
        compose.onNodeWithText("counts as sent", substring = true).assertHasNoClickAction()
    }

    @Test
    fun aFailedEditionDoesNotPreviewArticlesThatWentBackToWait() {
        today(EditionStatus.FAILED)

        compose.onNodeWithText("Story 0").assertDoesNotExist()
        compose.onNodeWithText("and 2 more").assertDoesNotExist()
    }
}
