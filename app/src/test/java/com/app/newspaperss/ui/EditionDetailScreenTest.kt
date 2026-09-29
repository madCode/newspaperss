package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
// Tall enough that every row of these short editions is composed without scrolling.
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class EditionDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val editionsDir by lazy { tmp.newFolder("editions") }
    private val repo by lazy { EditionRepository(db, editionsDir) }

    @After fun close() = db.close()

    /** An edition of [titles] (in reading order), all in [articleState]; returns the edition and article ids. */
    private fun edition(
        status: EditionStatus,
        titles: List<String>,
        articleState: ArticleState = if (status == EditionStatus.DELIVERED) ArticleState.DELIVERED else ArticleState.IN_EDITION,
        withFile: Boolean = true,
        minutes: (Int) -> Double = { 4.0 },
    ): Pair<Long, List<Long>> = runBlocking {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        val ids = titles.map { db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = it, url = "https://example.com/$it", title = it, state = articleState)) }
        if (withFile) editionsDir.resolve("e.epub").writeText("epub")
        val editionId = db.editions().insert(
            EditionEntity(title = "Tuesday Morning Edition", status = status, fileName = "e.epub", articleCount = titles.size, minutes = 12.0),
        )
        db.editions().insertArticles(
            titles.mapIndexed { i, title ->
                EditionArticleEntity(editionId = editionId, articleId = ids[i], position = i, title = title, sourceTitle = "Example News", minutes = minutes(i))
            }.reversed(),
        )
        editionId to ids
    }

    private fun show(editionId: Long): EditionDetailViewModel {
        val vm = EditionDetailViewModel(repo, editionId)
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        return vm
    }

    private fun waitFor(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun contentsAreListedInReadingOrderWithAtLeastAMinuteEach() {
        val (id, _) = edition(EditionStatus.READY, listOf("First story", "Second story", "Third story")) { if (it == 1) 0.2 else 6.4 }
        show(id)

        val tops = listOf("First story", "Second story", "Third story").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Example News · 1 min").assertIsDisplayed()
        compose.onNodeWithText("3 articles · about 12 min").assertIsDisplayed()
    }

    @Test
    fun broughtBackArticlesAreWaitingForTheNextEdition() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("Read it", "Missed one", "Missed two"))
        show(id)

        compose.onNodeWithText("Missed one").performClick()
        compose.onNodeWithText("Missed two").performClick()
        compose.onNodeWithText("Bring back 2").performClick()

        waitFor("2 articles will be in your next edition")
        val candidates = runBlocking { db.articles().candidates() }
        assertEquals(articles.drop(1).toSet(), candidates.map { it.id }.toSet())
        assertTrue(candidates.all { it.broughtBack })
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
        waitFor("Brought back for your next edition")
    }

    @Test
    fun onlyDeliveredEditionsOfferToBringArticlesBack() {
        val (id, _) = edition(EditionStatus.READY, listOf("Unsent story"))
        show(id)

        compose.onNodeWithText("Unsent story").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText("Bring back", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun sayingItWasSentUsesUpTheArticles() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        show(id)

        compose.onNodeWithText("I've sent it").performClick()

        waitFor("Sent on")
        assertEquals(EditionStatus.DELIVERED, runBlocking { db.editions().byId(id) }?.status)
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun aDeletedEpubCanNeitherBeSentNorOpened() {
        val (id, _) = edition(EditionStatus.READY, listOf("A story"), withFile = false)
        show(id)

        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Open").assertIsNotEnabled()
        compose.onNodeWithText("has been deleted", substring = true).assertIsDisplayed()
        compose.onNodeWithText("I've sent it").assertIsEnabled()
    }

    @Test
    fun tappingAnEarlierEditionOnTodayOpensIt() {
        val older = runBlocking {
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-09-28T06:30:00Z"), status = EditionStatus.DELIVERED))
        }
        runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-09-29T06:30:00Z"), status = EditionStatus.READY)) }
        val vm = TodayViewModel(repo, flowOf(null)) {}
        var opened: Long? = null
        compose.setContent { TodayScreen(vm, onOpenEdition = { opened = it }) }
        idleUntil { vm.state.value.editions?.size == 2 }

        compose.onNodeWithText("Monday Morning Edition").performClick()

        assertEquals(older, opened)
    }
}
