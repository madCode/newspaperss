package com.app.newspaperss.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.notes.NotesArticle
import com.app.newspaperss.core.notes.NotesEdition
import com.app.newspaperss.core.notes.NotesWriter
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.ui.today.BuildState
import com.app.newspaperss.work.EditionWorker
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
// Tall enough that every row of these short editions is composed without scrolling.
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class EditionDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    // In the app's files dir, like the notes: Open and Send hand the file out through its FileProvider.
    private val editionsDir by lazy { File(ApplicationProvider.getApplicationContext<Application>().filesDir, "editions").apply { mkdirs() } }
    private val repo by lazy { EditionRepository(db, editionsDir) }
    // In the app's files dir: the share sheet's FileProvider only serves files from there.
    private val notes by lazy {
        EditionNotes(db, File(ApplicationProvider.getApplicationContext<Application>().filesDir, "notes")) { ZoneOffset.UTC }
    }

    @After fun close() = db.close()

    @Before @After fun freshFileProvider() = clearFileProviderCache()

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

    private fun show(editionId: Long, preferOpen: Boolean = false): EditionDetailViewModel {
        val vm = EditionDetailViewModel(repo, editionId, notes)
        compose.setContent { EditionDetailScreen(vm, onBack = {}, preferOpen = preferOpen) }
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

        compose.onNodeWithContentDescription("Bring back Missed one").performClick()
        compose.onNodeWithContentDescription("Bring back Missed two").performClick()
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
    fun openingOnAPhoneDoesntCountAsDelivered() {
        val (onPhone, _) = edition(EditionStatus.READY, listOf("A story"))
        show(onPhone)
        compose.onNodeWithText("Open").performClick()
        assertEquals(Intent.ACTION_VIEW, shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.action)
        assertEquals("a phone may just be previewing it", EditionStatus.READY, runBlocking { db.editions().byId(onPhone) }?.status)
    }

    @Test
    fun openingOnABooxCountsAsDelivered() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        show(id, preferOpen = true)

        compose.onNodeWithText("Open").performClick()

        idleUntil { runBlocking { db.editions().byId(id) }?.status == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun sayingItWasSentUsesUpTheArticles() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        show(id)

        compose.onNodeWithText("I've sent it").performClick()

        waitFor("Sent")
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
    fun notesCarryEachArticlesDetailsAndOpenTheShareSheet() {
        val editionId = runBlocking {
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
            val kept = db.articles().insertIgnoring(
                ArticleEntity(
                    sourceId = source, guid = "a", url = "https://example.com/a", title = "Kept", author = "Jane Doe",
                    published = Instant.parse("2026-09-28T23:30:00Z"), state = ArticleState.DELIVERED,
                ),
            )
            val id = db.editions().insert(
                EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-09-29T06:30:00Z"), status = EditionStatus.DELIVERED),
            )
            db.editions().insertArticles(
                listOf(
                    EditionArticleEntity(editionId = id, articleId = kept, position = 0, title = "Kept", sourceTitle = "Example News", minutes = 4.0),
                    // Its source was removed: the edition still lists it, but its link and byline are gone.
                    EditionArticleEntity(editionId = id, articleId = null, position = 1, title = "Orphan", sourceTitle = "Old Blog", minutes = 3.0),
                ),
            )
            id
        }
        val vm = show(editionId)

        compose.onNodeWithText("Notes").performClick()
        idleUntil { File(notes.notesDir, "Tuesday Morning Edition notes.md").exists() }
        idleUntil { compose.waitForIdle(); vm.notesFile.value == null }

        val expected = NotesWriter.write(
            NotesEdition(
                "Tuesday Morning Edition",
                LocalDate.of(2026, 9, 29),
                listOf(
                    NotesArticle("Kept", "Example News", "https://example.com/a", "Jane Doe", LocalDate.of(2026, 9, 28)),
                    NotesArticle("Orphan", "Old Blog", url = null),
                ),
            ),
        )
        assertEquals(expected, File(notes.notesDir, "Tuesday Morning Edition notes.md").readText())
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/markdown", send.type)
        assertEquals("Tuesday Morning Edition notes.md", send.getStringExtra(Intent.EXTRA_TITLE))
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

    private fun work(state: WorkInfo.State, progress: androidx.work.Data = androidx.work.Data.EMPTY, output: androidx.work.Data = androidx.work.Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = output, progress = progress)

    @Test
    fun talkBackHearsTheBuildGoFromCheckingToItsResultButNotEveryCount() {
        val work = MutableStateFlow<WorkInfo?>(null)
        val vm = TodayViewModel(repo, work) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

        work.value = work(WorkInfo.State.RUNNING, progress = workDataOf(EditionWorker.STAGE to EditionWorker.STAGE_FETCHING, EditionWorker.FETCHED to 3))
        idleUntil { vm.state.value.build is BuildState.Fetching }
        compose.onNode(hasText("Making your edition") and live).assertExists()
        compose.onNode(hasText("3 articles read so far") and live).assertDoesNotExist()

        work.value = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        idleUntil { vm.state.value.build is BuildState.Failed }
        compose.onNode(hasText("Couldn't reach any of your sources.") and live).assertExists()
    }

    @Test
    fun anOldFailureIsShownButNotAnnouncedEachTimeTodayOpens() {
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        val vm = TodayViewModel(repo, flowOf(failed)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.build is BuildState.Failed }

        compose.onNodeWithText("Couldn't reach any of your sources.").assertExists()
        compose.onNode(hasText("Couldn't reach any of your sources.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertDoesNotExist()
    }

    @Test
    fun seeWhatsInsideOnTodayOpensTheLatestEdition() {
        val latest = runBlocking {
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, articleCount = 5, minutes = 30.0))
        }
        val vm = TodayViewModel(repo, flowOf(null)) {}
        var opened: Long? = null
        compose.setContent { TodayScreen(vm, onOpenEdition = { opened = it }) }
        idleUntil { vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("See what's inside").performClick()

        assertEquals(latest, opened)
    }

    private fun deleteFromTheScreen(id: Long) {
        var left = false
        val vm = EditionDetailViewModel(repo, id, notes)
        compose.setContent { EditionDetailScreen(vm, onBack = { left = true }) }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        idleUntil { left }
    }

    private fun isGone(id: Long) = runBlocking { repo.observeAll().first().none { it.id == id } && repo.observe(id).first() == null }

    @Test
    fun deletingAnUnsentEditionGivesItsArticlesToTheNextOne() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))

        deleteFromTheScreen(id)

        assertTrue(isGone(id))
        assertEquals(ArticleState.NEW, runBlocking { db.articles().byId(articles[0]) }?.state)
        assertFalse("its EPUB is gone", editionsDir.resolve("e.epub").exists())
    }

    @Test
    fun deletingASentEditionDoesntBringItsArticlesBack() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("A story"))

        deleteFromTheScreen(id)

        assertTrue(isGone(id))
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
        assertFalse("its EPUB is gone", editionsDir.resolve("e.epub").exists())
    }

    @Test
    fun aFolderCopyFinishingAfterADeleteDoesntBringItBack() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        runBlocking { repo.delete(id) }

        runBlocking { repo.markDelivered(id) }

        assertTrue(isGone(id))
        assertEquals("its articles stay with the next edition", ArticleState.NEW, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun anEditionBeingMadeCantBeDeleted() {
        val building = runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.BUILDING)) }

        assertFalse(runBlocking { repo.delete(building) })
        assertEquals(EditionStatus.BUILDING, runBlocking { db.editions().byId(building) }?.status)
    }
}
