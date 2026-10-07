package com.app.newspaperss.listen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.writeEpub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Listen playing made podcasts where they're made, and the phone's voice elsewhere. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastSpeakerTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()

    private val phone = FakeSpeaker()
    private val store by lazy { PodcastStore(tmp.newFolder("podcasts")) }
    private var inUse = true

    /** Audio that plays nothing: the test says how far in it is. */
    private val audio = object : PodcastAudio {
        override var listener: PodcastAudio.Listener? = null
        val played = mutableListOf<Triple<String, Long, Float>>()
        var stopped = 0
        override fun play(file: File, fromMs: Long, speed: Float) { played += Triple(file.name, fromMs, speed) }
        override fun stop() { stopped++ }
        override fun release() {}
    }

    private val speaker by lazy { PodcastSpeaker(phone, store, audio) { inUse } }
    private val heard = mutableListOf<String>()

    private fun listen() {
        speaker.listener = object : Speaker.Listener {
            override fun onStart(id: String) { heard += "start $id" }
            override fun onDone(id: String) { heard += "done $id" }
            override fun onError(id: String) { heard += "error $id" }
        }
    }

    /** Page 0 of edition 7 made in two pieces: lines 0-2 at 0, 2 and 4.5 s; lines 3-4 at 0 and 3 s. */
    private fun made(editionId: Long = 7, page: Int = 0) {
        store.want(editionId, PodcastVoice.EMMA)
        keep(editionId, page, 0, listOf(0.0, 2.0, 4.5))
        keep(editionId, page, 3, listOf(0.0, 3.0))
        store.complete(editionId, page)
    }

    private fun keep(editionId: Long, page: Int, first: Int, starts: List<Double>) {
        val scratch = store.scratch(editionId, page, first).apply { writeText("audio") }
        store.keep(editionId, page, first, scratch, starts)
    }

    /** A line of edition 7: five lines on a page, as made() makes them. */
    private fun id(line: Int, page: Int = 0, generation: Int = 1, lines: Int = 5) = LineId(generation, 7, page, line, lines).toString()

    @Test
    fun aMadeArticlePlaysFromItsPodcastReportingEachSentenceAsItsReached() {
        made()
        listen()
        assertTrue(speaker.speak(id(0), "Quanta", null, 1.2f, flush = true))
        // The line queued behind it needs nothing: the audio goes on through it.
        assertTrue(speaker.speak(id(1), "Counting Knots", null, 1.2f, flush = false))

        assertEquals(listOf(Triple("0-0.m4a", 0L, 1.2f)), audio.played)
        assertTrue(phone.said.isEmpty())
        assertEquals(PodcastVoice.EMMA, speaker.voice.value)

        audio.listener!!.onPosition(100)
        audio.listener!!.onPosition(4_600)
        assertEquals(listOf("start ${id(0)}", "start ${id(1)}", "start ${id(2)}"), heard)

        // The first piece ends: the next plays on, and the last line's end ends the article.
        audio.listener!!.onEnded()
        assertEquals(Triple("0-3.m4a", 0L, 1.2f), audio.played.last())
        audio.listener!!.onPosition(3_100)
        audio.listener!!.onEnded()
        assertEquals(listOf("start ${id(3)}", "start ${id(4)}", "done ${id(4)}"), heard.drop(3))
    }

    @Test
    fun startingPartWayPlaysFromThatSentenceInItsPiece() {
        made()
        listen()
        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)

        assertEquals(listOf(Triple("0-3.m4a", 3_000L, 1f)), audio.played)
        audio.listener!!.onPosition(3_000)
        // Only from the sentence asked for.
        assertEquals(listOf("start ${id(4)}"), heard)
    }

    @Test
    fun anArticleNotMadeYetIsReadInThePhonesVoice() {
        made(page = 0)
        speaker.speak(id(0, page = 1), "Lagos Drivers", null, 1f, flush = true)

        assertEquals("Lagos Drivers", phone.said.single().text)
        assertTrue(audio.played.isEmpty())
        assertNull(speaker.voice.value)
    }

    @Test
    fun theVoiceChangesOnlyBetweenArticles() {
        // Started in the phone's voice; made while it's listened to; a jump within it stays put.
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        made()
        speaker.speak(id(3, generation = 2), "It took a week.", null, 1f, flush = true)
        assertEquals(2, phone.said.size)
        assertTrue(audio.played.isEmpty())

        // The next article, made, plays from its podcast; coming back, so does this one.
        made(page = 1)
        speaker.speak(id(0, page = 1, generation = 3), "Lagos Drivers", null, 1f, flush = true)
        speaker.speak(id(0, generation = 4), "Quanta", null, 1f, flush = true)
        assertEquals(2, audio.played.size)
    }

    @Test
    fun withKokoroTurnedOffItsAllThePhonesVoice() {
        made()
        inUse = false
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        assertEquals(1, phone.said.size)
        assertTrue(audio.played.isEmpty())
    }

    @Test
    fun aSentenceOutsideAnEditionStopsThePodcastAndUsesThePhonesVoice() {
        made()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        speaker.speak("sample", "This is how I sound.", null, 1f, flush = true)
        assertEquals(1, audio.stopped)
        assertEquals("This is how I sound.", phone.said.single().text)
    }

    @Test
    fun stoppingStopsBoth() {
        made()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        speaker.stop()
        assertEquals(1, audio.stopped)
        assertTrue(phone.queue.isEmpty())
    }

    @Test
    fun audioThatCantBePlayedHandsOverToThePhonesVoiceWhereItWas() {
        made()
        listen()
        // The player queues each line as the one before starts.
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        speaker.speak(id(1), "Counting Knots", null, 1f, flush = false)
        audio.listener!!.onPosition(2_100)
        speaker.speak(id(2), "Nobody knew.", null, 1f, flush = false)
        audio.listener!!.onError()

        // From the sentence it had reached, with the one queued behind it.
        assertEquals(listOf("Counting Knots", "Nobody knew."), phone.said.map { it.text })
        assertEquals(listOf(id(1), id(2)), phone.said.map { it.id })
        assertNull(speaker.voice.value)
        // A tick from the stopped audio reports nothing.
        val before = heard.size
        audio.listener!!.onPosition(5_000)
        assertEquals(before, heard.size)
    }

    @Test
    fun aNextPieceThatCantBePlayedCarriesOnFromTheSentenceAfterTheLastHeard() {
        made()
        listen()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        speaker.speak(id(1), "Counting Knots", null, 1f, flush = false)
        audio.listener!!.onPosition(4_600)
        speaker.speak(id(3), "It took a week.", null, 1f, flush = false)
        // The first piece played through; the second fails to start.
        audio.listener!!.onEnded()
        audio.listener!!.onError()

        assertEquals(listOf("It took a week."), phone.said.map { it.text })
        assertEquals(id(3), phone.said.single().id)
    }

    @Test
    fun theVoicesNameGoesWhenListeningStops() {
        made()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        assertEquals(PodcastVoice.EMMA, speaker.voice.value)
        speaker.stop()
        assertNull(speaker.voice.value)
        // Played again, from the same article.
        speaker.speak(id(1, generation = 2), "Counting Knots", null, 1f, flush = true)
        assertEquals(PodcastVoice.EMMA, speaker.voice.value)
    }

    @Test
    fun kokoroTurnedOffPartWayIsHeardAtTheNextPlay() {
        made()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        inUse = false
        // Paused and played again.
        speaker.speak(id(1, generation = 2), "Counting Knots", null, 1f, flush = true)
        assertEquals("Counting Knots", phone.said.single().text)
        assertEquals(1, audio.played.size)
    }

    @Test
    fun aPodcastRemovedPartWayIsntPlayedFromFilesThatAreGone() {
        made()
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        store.deleteAll()
        speaker.speak(id(3, generation = 2), "It took a week.", null, 1f, flush = true)
        assertEquals("It took a week.", phone.said.single().text)
        assertEquals(1, audio.played.size)
    }

    @Test
    fun aPodcastMadeFromOtherSentencesThanTheBooksIsntUsed() {
        made()
        // The book now splits the page into six sentences; the podcast has five.
        speaker.speak(id(0, lines = 6), "Quanta", null, 1f, flush = true)
        assertEquals(1, phone.said.size)
        assertTrue(audio.played.isEmpty())
    }

    @Test
    fun itSaysWhyAnArticleOfAnEditionWithAPodcastPlaysInThePhonesVoice() {
        made(page = 0)
        store.leaveLive(7, 1)
        speaker.speak(id(0), "Quanta", null, 1f, flush = true)
        assertNull(speaker.instead.value)
        speaker.speak(id(0, page = 1), "Le Monde", null, 1f, flush = true)
        assertEquals(PodcastSpeaker.Instead.LEFT_OUT, speaker.instead.value)
        speaker.speak(id(0, page = 2), "Rest of World", null, 1f, flush = true)
        assertEquals(PodcastSpeaker.Instead.NOT_MADE_YET, speaker.instead.value)
        // An edition without a podcast needs no word about it.
        speaker.speak(LineId(1, 8, 0, 0, 5).toString(), "Nautilus", null, 1f, flush = true)
        assertNull(speaker.instead.value)
    }

    @Test
    fun theListenPlayerFollowsThePodcastThenReadsTheNextArticleInThePhonesVoice() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val editionsDir = tmp.newFolder("editions")
        val repo = EditionRepository(dbRule.db, editionsDir)
        val articles = listOf(
            EditionArticle("Counting Knots", "Quanta", "https://example.com/knots", "<p>Nobody knew. Then a teacher counted.</p>", 2.0),
            EditionArticle("Lagos Drivers", "Rest of World", "https://example.com/lagos", "<p>The drivers met.</p>", 1.0),
        )
        val editionId = runBlocking {
            File(editionsDir, "t.epub").writeEpub(articles)
            val id = dbRule.db.editions().insert(EditionEntity(title = "T", status = EditionStatus.DELIVERED, fileName = "t.epub", articleCount = 2, minutes = 3.0))
            dbRule.db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = null, position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
            id
        }
        // The first article made: its kicker, title and two sentences, 2 seconds each.
        store.want(editionId, PodcastVoice.HEART)
        val scratch = store.scratch(editionId, 0, 0).apply { writeText("audio") }
        store.keep(editionId, 0, 0, scratch, listOf(0.0, 2.0, 4.0, 6.0))
        store.complete(editionId, 0)

        val player = ListenPlayer(speaker, StoredListenProgress(app), open = { ListenBook.open(repo, it) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        player.start(editionId)
        idleUntil { audio.played.isNotEmpty() }
        audio.listener!!.onPosition(4_100)
        assertEquals(2, player.state.value.at.line)

        audio.listener!!.onEnded()
        idleUntil { phone.said.isNotEmpty() }
        assertEquals(1, player.state.value.at.page)
        assertEquals("Rest of World", phone.said.first().text)
        assertNull(speaker.voice.value)
    }
}
