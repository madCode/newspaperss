package com.app.newspaperss.listen

import android.app.Application
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.transparentPng
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.listen.ListenScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/** Listening to a real edition's book: the player, the edition page's button, the playing screen and the media controls. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class ListenAppTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val editionsDir = File(app.filesDir, "editions").apply { mkdirs() }
    private val repo = EditionRepository(db, editionsDir)
    private val speaker = FakeSpeaker()
    private val progress = StoredListenProgress(app)
    private val player = ListenPlayer(speaker, progress, open = { ListenBook.open(repo, it) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    private var connected = 0
    private val listening = Listening(player, progress, repo) { connected++ }

    private val knots = EditionArticle(
        title = "The Mathematician Who Counted Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/knots",
        bodyHtml = "<p>Nobody could say how many knots there are. Then a teacher wrote a program.</p>" +
            "<figure><img src=\"images/table.png\"/><figcaption>Tait's table of knots</figcaption></figure>" +
            "<p>The count took a week.</p>",
        minutes = 4.0, author = "Erica Klarreich", images = listOf(EpubImage("images/table.png", "image/png", transparentPng(40, 30))),
    )
    private val lagos = EditionArticle(
        title = "Lagos Drivers Built Their Own App", sourceTitle = "Rest of World", url = "https://example.com/lagos",
        bodyHtml = "<p>The drivers met in a car park.</p>", minutes = 2.0,
    )

    /** An edition with its book written; returns its id. */
    private fun edition(title: String = "Thursday Morning Edition", vararg articles: EditionArticle = arrayOf(knots, lagos)): Long = runBlocking {
        val file = "${title.hashCode()}.epub"
        FileOutputStream(File(editionsDir, file)).use {
            EpubWriter.write(EditionDoc(title, LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-00000000000${articles.size}", listOf(EditionSection(null, articles.toList()))), it)
        }
        val id = db.editions().insert(EditionEntity(title = title, status = EditionStatus.DELIVERED, fileName = file, articleCount = articles.size, minutes = articles.sumOf { it.minutes }))
        db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = null, position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
        id
    }

    private fun startAndWait(id: Long) {
        listening.start(id)
        idleUntil { player.state.value.script != null }
    }

    @Test
    fun theBookIsReadArticleByArticleWithItsPicturesThenItsClosingPage() {
        val id = edition()
        startAndWait(id)
        val heard = mutableListOf<String>()
        while (speaker.queue.isNotEmpty()) {
            heard += speaker.sayNext()
            idleUntil { speaker.queue.isNotEmpty() || player.state.value.finished }
        }
        assertEquals(
            listOf(
                "Quanta Magazine", "The Mathematician Who Counted Knots", "By Erica Klarreich",
                "Nobody could say how many knots there are.", "Then a teacher wrote a program.", "Image: Tait's table of knots", "The count took a week.",
                "Rest of World", "Lagos Drivers Built Their Own App", "The drivers met in a car park.",
            ),
            heard.take(10),
        )
        assertEquals("That's all for today.", heard[10])
        assertTrue(progress.finished(id))
        assertEquals(1, connected)
        runBlocking { assertNotNull(player.image("images/table.png")) }
    }

    @Test
    fun whereItWasLeftIsKeptForTheMostRecentEditions() {
        val stored = StoredListenProgress(app, now = { System.nanoTime() })
        for (id in 1L..12L) stored.set(id, ListenPosition(1, 2))
        assertNull(stored.get(1))
        assertEquals(ListenPosition(1, 2), stored.get(12))
        assertEquals(12L, stored.unfinished().first())
        stored.finish(12)
        assertTrue(stored.finished(12))
        assertFalse(12L in stored.unfinished())
        assertEquals(10, stored.unfinished().size + 1)
    }

    @Test
    fun theEditionPageOffersListenThenResumeAndYesterdaysPaperFirst() {
        val yesterday = edition("Wednesday Morning Edition", lagos)
        val today = edition()
        progress.set(yesterday, ListenPosition(0, 1))
        val vm = EditionDetailViewModel(repo, today, EditionNotes(db, File(app.filesDir, "notes"))) {}
        var openedPlayer = 0
        compose.setContent { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = { openedPlayer++ }) }
        // 6 minutes to read is 9 to hear.
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finish Wednesday Morning Edition first?").assertIsDisplayed()
        compose.onNodeWithText("Finish it").performClick()
        idleUntil { player.state.value.script != null }
        assertEquals(yesterday, player.state.value.editionId)
        // Where it was left: its second line.
        assertEquals("Lagos Drivers Built Their Own App", speaker.queue.first().text)
        assertEquals(1, openedPlayer)

        // Playing yesterday's; today's still says Listen, and starting it now is the choice made.
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Start this one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start this one").performClick()
        idleUntil { player.state.value.editionId == today && player.state.value.script != null }
        compose.onNodeWithText("Listening · Open player").performClick()
        assertEquals(3, openedPlayer)

        player.next()
        player.pause()
        compose.onNodeWithText("Resume · 3 min left").assertIsDisplayed()
    }

    @Test
    fun thePlayingScreenFollowsTheVoiceAndATappedSentenceIsReadFromThere() {
        val id = edition()
        startAndWait(id)
        compose.setContent { ListenScreen(player, onBack = {}) }
        compose.onNodeWithText("Listening · 1 of 2").assertIsDisplayed()
        compose.onNodeWithText("Tait's table of knots").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").performClick()
        assertFalse(player.state.value.playing)
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue(player.state.value.playing)

        // The paragraph's second sentence: near the end of its text.
        compose.onNodeWithText("Nobody could say", substring = true).performTouchInput { click(centerRight.copy(x = right - 4f)) }
        idleUntil { speaker.queue.firstOrNull()?.text == "Then a teacher wrote a program." }
        compose.onNodeWithContentDescription("Next sentence").performClick()
        assertEquals("Image: Tait's table of knots", speaker.queue.first().text)
        compose.onNodeWithContentDescription("Back a sentence").performClick()
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil { player.state.value.at.page == 1 }
        compose.onNodeWithText("Listening · 2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous article").performClick()
        idleUntil { player.state.value.at.page == 0 }

        compose.onNodeWithContentDescription("Speed 1×. Change speed").performClick()
        assertEquals(1.2f, player.state.value.speed)

        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNode(hasText("Lagos Drivers Built Their Own App") and hasAnyAncestor(isDialog())).performClick()
        idleUntil { player.state.value.at.page == 1 }
    }

    @Test
    fun scrollingAwayStopsFollowingUntilAsked() {
        startAndWait(edition())
        compose.setContent { ListenScreen(player, onBack = {}) }
        compose.onNodeWithText("The count took a week.").performTouchInput { swipeDown() }
        compose.onNodeWithText("Back to where it's reading").performClick()
        assertEquals(0, compose.onAllNodesWithText("Back to where it's reading").fetchSemanticsNodes().size)
    }

    @Test
    fun anArticleWithNoVoiceForItsLanguageOffersToGetOne() {
        speaker.installed = setOf("en")
        startAndWait(edition("Édition", knots.copy(language = "fr")))
        compose.setContent { ListenScreen(player, onBack = {}) }
        compose.onNodeWithText("Get a French voice").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, started.action)
    }

    @Test
    fun withNothingPlayingTheScreenSaysHowToStart() {
        compose.setContent { ListenScreen(player, onBack = {}) }
        compose.onNodeWithText("Nothing is playing. Open an edition and tap Listen.").assertIsDisplayed()
    }

    @Test
    fun theLockScreensControlsMoveByArticleAndBySentence() {
        val id = edition()
        startAndWait(id)
        val session = SessionPlayer(player)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(3, session.mediaItemCount)
        assertEquals("The Mathematician Who Counted Knots", session.mediaMetadata.title)
        assertEquals("Quanta Magazine", session.mediaMetadata.artist)
        assertTrue(session.playWhenReady)
        session.seekForward()
        assertEquals(1, player.state.value.at.line)
        // Just started, so back goes to the sentence before.
        speaker.startNext()
        session.seekBack()
        assertEquals(0, player.state.value.at.line)
        session.seekToNext()
        idleUntil { player.state.value.at.page == 1 }
        session.seekToPrevious()
        idleUntil { player.state.value.at.page == 0 }
        session.seekToDefaultPosition(1)
        idleUntil { player.state.value.at.page == 1 }
        session.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, player.state.value.speed)
        session.pause()
        assertFalse(player.state.value.playing)
        session.play()
        assertTrue(player.state.value.playing)
        session.stop()
        assertFalse(player.state.value.playing)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Player.STATE_READY, session.playbackState)
        session.release()
    }
}
