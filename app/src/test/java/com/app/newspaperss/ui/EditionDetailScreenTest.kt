package com.app.newspaperss.ui

import com.app.newspaperss.settings.offersOpen
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.Device
import android.app.Application
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.notes.NotesArticle
import com.app.newspaperss.core.notes.NotesEdition
import com.app.newspaperss.core.notes.NotesWriter
import com.app.newspaperss.core.notes.Reflection
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.KINDLE_NOTE
import com.app.newspaperss.ui.edition.MARK_NOT_SENT
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.ui.today.BuildState
import com.app.newspaperss.ui.today.BUILD_STATUS
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
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    // In the app's files dir, like the notes: Open and Send hand the file out through its FileProvider.
    private val editionsDir by lazy { File(ApplicationProvider.getApplicationContext<Application>().filesDir, "editions").apply { mkdirs() } }
    private val repo by lazy { EditionRepository(db, editionsDir) }
    // In the app's files dir: the share sheet's FileProvider only serves files from there.
    private val notes by lazy {
        EditionNotes(db, File(ApplicationProvider.getApplicationContext<Application>().filesDir, "notes")) { ZoneOffset.UTC }
    }

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

    private fun show(editionId: Long, preferOpen: Boolean = false, offerOpen: Boolean = true): EditionDetailViewModel {
        val vm = EditionDetailViewModel(repo, editionId, notes) {}
        compose.setContent { EditionDetailScreen(vm, onBack = {}, preferOpen = preferOpen, offerOpen = offerOpen) }
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
    fun theStarOnADeliveredEditionBringsAnArticleBackAndUnstarringUndoesIt() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("Read it", "Missed one"))
        show(id)
        compose.onNodeWithText("Didn't get to one? Tap ☆ to bring it back.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Put Missed one in your next edition").assertIsOff().performClick()

        waitFor("Starred for your next edition")
        compose.onNodeWithContentDescription("Put Missed one in your next edition").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred"))
        assertEquals(listOf(articles[1]), runBlocking { db.articles().candidates() }.map { it.id })

        compose.onNodeWithContentDescription("Put Missed one in your next edition").performClick()
        idleUntil { runBlocking { db.articles().candidates() }.isEmpty() }
        assertEquals("unstarred, it's delivered as before", ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[1]) }?.state)
    }

    @Test
    fun articlesThatWentInStarredSaySoInWords() {
        val (id, _) = edition(EditionStatus.READY, listOf("Starred story", "Plain story"))
        db.openHelper.writableDatabase.execSQL("UPDATE edition_articles SET starred = 1 WHERE title = 'Starred story'")
        show(id)

        waitFor("Example News · 4 min · You starred it")
        compose.onNodeWithText("Example News · 4 min").assertIsDisplayed()
    }

    @Test
    fun aReadyEditionHasNoStarToggles() {
        val (id, _) = edition(EditionStatus.READY, listOf("Unsent story"))
        show(id)

        compose.onNodeWithText("Unsent story").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText("Next edition", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("in your next edition", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun anArticleWhoseSourceWasRemovedHasNoStar() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("Gone story"))
        runBlocking { db.sources().delete(db.sources().all().single()) }
        show(id)

        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasContentDescription("in your next edition", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun todaySaysHowManyStarredArticlesAreWaitingOnlyWhenThereAreSome() {
        val (_, articles) = edition(EditionStatus.DELIVERED, listOf("One", "Two"))
        val vm = TodayViewModel(repo, flowOf(null)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.editions != null }
        assertEquals(0, compose.onAllNodes(hasContentDescription("starred", substring = true)).fetchSemanticsNodes().size)

        runBlocking { articles.forEach { repo.setStarred(it, true) } }

        idleUntil { compose.onAllNodes(hasContentDescription("2 starred articles are waiting for your next edition")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("2 starred articles are waiting for your next edition").assertIsDisplayed()
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
    fun aKindleReaderIsOfferedSendButNotOpen() {
        // Open would put the book on the phone, where a Kindle reader doesn't read it.
        val (id, _) = edition(EditionStatus.READY, listOf("A story"))
        show(id, offerOpen = Device.KINDLE.offersOpen)
        compose.onNodeWithText("Send").assertExists()
        compose.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test
    fun todayOffersAKindleReaderSendButNotOpen() {
        edition(EditionStatus.READY, listOf("A story"))
        val vm = TodayViewModel(repo, flowOf(null), settings = flowOf(Settings(device = Device.KINDLE))) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasText("Send")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").assertDoesNotExist()
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
                question = Reflection.forEdition(editionId),
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
        // A new reader's first build: the "make your first edition" prompt goes away as it starts.
        val work = MutableStateFlow<WorkInfo?>(null)
        val vm = TodayViewModel(repo, work) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.editions != null }
        // TalkBack announces a live region whose text changes, not one that appears, so the status
        // must stay the same node from the tap to the result.
        val status = compose.onNodeWithTag(BUILD_STATUS).fetchSemanticsNode().id
        fun statusNode() = compose.onNodeWithTag(BUILD_STATUS).fetchSemanticsNode()
        fun text() = statusNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

        work.value = work(WorkInfo.State.ENQUEUED)
        idleUntil { vm.state.value.build == BuildState.Syncing }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Checking your sources for new articles…", text())
        assertTrue(SemanticsProperties.LiveRegion in statusNode().config)

        work.value = work(WorkInfo.State.RUNNING, progress = workDataOf(EditionWorker.STAGE to EditionWorker.STAGE_FETCHING, EditionWorker.FETCHED to 3))
        idleUntil { vm.state.value.build is BuildState.Fetching }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Making your edition", text())
        compose.onNode(hasText("3 articles so far") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertDoesNotExist()

        work.value = work(WorkInfo.State.SUCCEEDED)
        idleUntil { vm.state.value.build == BuildState.Idle }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Your edition is ready.", text())
    }

    @Test
    fun aFailedBuildIsAnnounced() {
        val work = MutableStateFlow<WorkInfo?>(work(WorkInfo.State.ENQUEUED))
        val vm = TodayViewModel(repo, work) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.build == BuildState.Syncing }

        work.value = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        idleUntil { vm.state.value.build is BuildState.Failed }
        compose.onNode(hasText("Couldn't reach any of your sources.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test
    fun aBuildQueuedWithoutAConnectionSaysItsWaitingForOne() {
        val online = MutableStateFlow(false)
        val vm = TodayViewModel(repo, flowOf(work(WorkInfo.State.ENQUEUED)), online = online) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }

        idleUntil { vm.state.value.build == BuildState.WaitingForNetwork }
        compose.onNodeWithText("Waiting for an internet connection…").assertExists()

        online.value = true
        idleUntil { vm.state.value.build == BuildState.Syncing }
        compose.onNodeWithText("Checking your sources for new articles…").assertExists()
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
    fun onceAnEditionIsSentAnotherIsOfferedButNotPushed() {
        // An edition that ends: after today's is sent, making another is a quiet option.
        edition(EditionStatus.DELIVERED, listOf("A story"))
        var started = 0
        val vm = TodayViewModel(repo, flowOf(null)) { started++ }
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("Make an edition now").assertDoesNotExist()
        compose.onNodeWithText("Make another edition").performClick()
        assertEquals(1, started)
    }

    @Test
    fun aFailedEditionOffersOneWayToTryAgain() {
        // The build that made it failed too, with the same reason.
        runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.FAILED, error = EditionBuilder.UNEXPECTED)) }
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to EditionBuilder.UNEXPECTED))
        val vm = TodayViewModel(repo, flowOf(failed)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.editions?.size == 1 && vm.state.value.build is BuildState.Failed }

        assertEquals("said once", 1, compose.onAllNodes(hasText(EditionBuilder.UNEXPECTED)).fetchSemanticsNodes().size)
        // In the status line, where TalkBack announces a build's result.
        compose.onNodeWithTag(BUILD_STATUS).assertTextEquals(EditionBuilder.UNEXPECTED)

        assertEquals(1, compose.onAllNodes(hasText("Try again") and hasClickAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Make another edition").assertDoesNotExist()
        compose.onNodeWithText("Make an edition now").assertDoesNotExist()
    }

    @Test
    fun aReadyEditionsSendComesBeforeARetry() {
        // A run can fail after making an edition; sending that one comes first.
        edition(EditionStatus.READY, listOf("A story"))
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Something went wrong."))
        val vm = TodayViewModel(repo, flowOf(failed)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.build is BuildState.Failed && vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Make another edition").assertExists()
    }

    @Test
    fun aFailedBuildAfterASentEditionOffersToTryAgain() {
        edition(EditionStatus.DELIVERED, listOf("A story"))
        var started = 0
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        val vm = TodayViewModel(repo, flowOf(failed)) { started++ }
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.build is BuildState.Failed }

        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, started)
    }

    @Test
    fun makeAnotherEditionSitsBelowTheLatestWhetherSentOrNot() {
        // One place for it, whether the edition is waiting to be sent or already sent.
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val vm = TodayViewModel(repo, flowOf(null)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.editions?.size == 1 }
        fun below() = compose.onNodeWithText("Make another edition").fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithText("Tuesday Morning Edition").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("below a sent edition", below())

        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = EditionStatus.READY)) }
        idleUntil { vm.state.value.editions?.single()?.status == EditionStatus.READY }
        compose.waitForIdle()
        assertTrue("below a ready edition", below())
    }

    @Test
    fun nothingNewAfterAnEditionSaysSoAsFinished() {
        edition(EditionStatus.DELIVERED, listOf("A story"))
        val nothing = work(WorkInfo.State.SUCCEEDED, output = workDataOf(EditionWorker.NOTHING_NEW to true))
        val vm = TodayViewModel(repo, flowOf(nothing)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        idleUntil { vm.state.value.build == BuildState.NothingNew && vm.state.value.editions?.size == 1 }

        // Not "add sources": this reader has an edition, and there's just nothing since.
        compose.onNodeWithTag(BUILD_STATUS).assertTextEquals("Nothing new since your last edition. Check back later.")
    }

    @Test
    fun tappingTodaysCardOpensTheLatestEdition() {
        val latest = runBlocking {
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, articleCount = 5, minutes = 30.0))
        }
        val vm = TodayViewModel(repo, flowOf(null)) {}
        var opened: Long? = null
        compose.setContent { TodayScreen(vm, onOpenEdition = { opened = it }) }
        idleUntil { vm.state.value.editions?.size == 1 }

        compose.onNode(hasText("Tuesday Morning Edition") and hasClickAction()).performClick()

        assertEquals(latest, opened)
    }

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id) }?.status

    /** Mark as not sent from the menu, [options] being its button's description. */
    private fun markNotSent(options: String) {
        compose.onNodeWithContentDescription(options).performClick()
        compose.onNodeWithText(MARK_NOT_SENT).performClick()
        compose.onNode(hasText("Mark as not sent") and hasAnyAncestor(isDialog())).performClick()
    }

    private val todaysOptions = "More options for Tuesday Morning Edition"

    @Test
    fun aSentEditionThatNeverArrivedCanBeMarkedAsNotSentAfterAsking() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("A story"))
        show(id)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(MARK_NOT_SENT).performClick()
        compose.onNodeWithText("Mark “Tuesday Morning Edition” as not sent?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelling changes nothing", EditionStatus.DELIVERED, statusOf(id))

        markNotSent("More options")

        idleUntil { statusOf(id) == EditionStatus.READY }
        assertEquals(ArticleState.IN_EDITION, runBlocking { db.articles().byId(articles[0]) }?.state)
        waitFor("I've sent it")
        compose.onNodeWithText("Send").assertExists()
    }

    @Test
    fun aSentEditionWhoseFileIsGoneCantBeMarkedAsNotSent() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"), withFile = false)
        show(id)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Send again").assertIsNotEnabled()
        compose.onNodeWithText(MARK_NOT_SENT).assertDoesNotExist()
    }

    @Test
    fun todaysSentEditionCanBeMarkedAsNotSentAndSentAgain() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val vm = TodayViewModel(repo, flowOf(null), settings = flowOf(Settings(device = Device.KINDLE))) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("· sent")

        markNotSent(todaysOptions)

        idleUntil { statusOf(id) == EditionStatus.READY }
        waitFor("I've sent it")
    }

    @Test
    fun afterASendWithTheKindleAppTodaySaysItCanTakeAFewMinutes() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val kindle = MutableStateFlow(emptyMap<Long, KindleSend>())
        val vm = TodayViewModel(repo, flowOf(null), sentToKindle = kindle) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("· sent")
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()

        kindle.value = mapOf(id to KindleSend.APP)
        waitFor(KINDLE_NOTE)

        // Marked as not sent, it's waiting to be sent again: the note would contradict that.
        markNotSent(todaysOptions)
        waitFor("I've sent it")
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()
    }

    @Test
    fun anEditionJustSentWithTheKindleAppSaysItCanTakeAFewMinutes() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        // Another edition sent with Kindle says nothing about this one.
        val kindle = MutableStateFlow(mapOf(id + 1 to KindleSend.APP))
        val vm = EditionDetailViewModel(repo, id, notes, sentToKindle = kindle) {}
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        compose.waitForIdle()
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()

        kindle.value = mapOf(id to KindleSend.APP)
        waitFor(KINDLE_NOTE)
        compose.onNodeWithText(KINDLE_NOTE).assertIsDisplayed()
    }

    private val dismissed = mutableListOf<Long>()

    private fun deleteFromTheScreen(id: Long) {
        var left = false
        val vm = EditionDetailViewModel(repo, id, notes) { dismissed += it }
        compose.setContent { EditionDetailScreen(vm, onBack = { left = true }) }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete edition").performClick()
        compose.onNode(hasText("Delete edition") and hasAnyAncestor(isDialog())).performClick()
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
        assertEquals("its Send notification goes too", listOf(id), dismissed)
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
    fun deletingAsksByNameAndSaysWhatHappensToTheArticles() {
        val (id, _) = edition(EditionStatus.READY, listOf("A story"))
        show(id)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete edition").performClick()

        compose.onNodeWithText("Delete Tuesday Morning Edition?").assertIsDisplayed()
        compose.onNodeWithText("It hasn't been sent, so its articles go into your next edition.").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(EditionStatus.READY, runBlocking { db.editions().byId(id) }?.status)
    }

    @Test
    fun anEditionBeingMadeCantBeDeleted() {
        val building = runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.BUILDING)) }

        assertFalse(runBlocking { repo.delete(building) })
        val vm = EditionDetailViewModel(repo, building, notes) {}
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        idleUntil { vm.detail.value?.edition != null }
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        assertEquals(EditionStatus.BUILDING, runBlocking { db.editions().byId(building) }?.status)
    }
}
