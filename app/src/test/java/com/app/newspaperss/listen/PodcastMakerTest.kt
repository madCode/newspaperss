package com.app.newspaperss.listen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.writeEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Making podcasts from real editions' books, with a stand-in for Kokoro and the encoder. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastMakerTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db

    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }
    private val editionsDir by lazy { tmp.newFolder("editions") }
    private val editions by lazy { EditionRepository(db, editionsDir) }
    private val store by lazy { PodcastStore(tmp.newFolder("podcasts")) }
    private val install by lazy {
        KokoroInstall(tmp.newFolder("kokoro")) { listOf(KokoroFile("tokens.txt", 6, "ce013625030ba8dba906f756967f9e9ca394464a")) }.also {
            it.file("tokens.txt").writeText("hello\n")
            it.markVerified("tokens.txt")
        }
    }

    /** Everything said, in order, and in which voice. */
    private val said = mutableListOf<String>()
    private val voices = mutableListOf<PodcastVoice>()
    private var released = 0
    /** Called after each line is said, to stop or change things part way. */
    private var afterLine: (String) -> Unit = {}

    /** A Kokoro making ten samples a character, at 1000 a second. */
    private val engine = { voice: PodcastVoice ->
        voices += voice
        object : PodcastEngine {
            override fun speak(text: String): Speech {
                said += text
                afterLine(text)
                return Speech(FloatArray(text.length * 10), RATE)
            }
            override fun release() { released++ }
        }
    }

    /** Writes the samples' count, so a page's file says how much went into it. */
    private val encoder = AudioEncoder { file, rate ->
        assertEquals(RATE, rate)
        object : AudioSink {
            var count = 0
            override fun write(samples: FloatArray) { count += samples.size }
            override fun close() = file.writeText(count.toString())
        }
    }

    private var started = 0
    private val maker by lazy { PodcastMaker(editions, store, install, settings, engine, encoder) { started++ } }

    private val knots = EditionArticle(
        title = "Counting Knots", sourceTitle = "Quanta", url = "https://example.com/knots",
        bodyHtml = "<p>Nobody knew. Then a teacher counted.</p><p>It took a week.</p>", minutes = 2.0,
    )
    private val french = EditionArticle(
        title = "Le Tour", sourceTitle = "Le Monde", url = "https://example.com/tour",
        bodyHtml = "<p>Le peloton est parti.</p>", minutes = 1.0, language = "fr",
    )
    private val lagos = EditionArticle(
        title = "Lagos Drivers", sourceTitle = "Rest of World", url = "https://example.com/lagos",
        bodyHtml = "<p>The drivers met.</p>", minutes = 1.0,
    )

    private fun edition(title: String, vararg articles: EditionArticle): Long = runBlocking {
        val file = "${title.hashCode()}.epub"
        File(editionsDir, file).writeEpub(articles.toList(), title = title)
        val id = db.editions().insert(EditionEntity(title = title, status = EditionStatus.DELIVERED, fileName = file, articleCount = articles.size, minutes = articles.sumOf { it.minutes }))
        db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = null, position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
        id
    }

    private fun inUse(voice: PodcastVoice = PodcastVoice.HEART) = runBlocking {
        settings.update { it.copy(listenVoice = ListenVoice.PODCAST, podcastVoice = voice, podcastPace = 1.2f) }
    }

    private fun request(id: Long) = runBlocking { maker.request(id) }

    private fun make() = runBlocking { maker.makeAll() }

    @Test
    fun eachPageIsMadeInBookOrderWithWhenEachSentenceStarts() {
        inUse()
        val id = edition("Thursday", knots, lagos)
        request(id)
        assertEquals(1, started)
        make()

        assertTrue(store.finished(id))
        assertTrue(store.waiting().isEmpty())
        // Two articles, then the closing page.
        assertNotNull(store.audio(id, 0))
        assertNotNull(store.audio(id, 1))
        assertNotNull(store.audio(id, 2))
        assertEquals(listOf("Quanta", "Counting Knots", "Nobody knew.", "Then a teacher counted.", "It took a week."), said.take(5))
        // Each line starts where the last ended: straight after it within a paragraph, after a
        // breath between them. "Quanta" is 60 samples, "Nobody knew." 120.
        val starts = store.starts(id, 0)!!
        assertEquals(0.0, starts[0], 0.0)
        assertEquals(0.06 + 0.35, starts[1], 0.001)
        assertEquals(0.12, starts[3] - starts[2], 0.001)
        val samples = store.audio(id, 0)!!.readText().toInt()
        assertTrue("the breaths are in the audio too", samples > said.take(5).sumOf { it.length * 10 })
        assertEquals(listOf(PodcastVoice.HEART), voices)
        assertEquals(1, released)
    }

    @Test
    fun anArticleInAnotherLanguageIsLeftToThePhonesVoice() {
        inUse()
        val id = edition("Thursday", knots, french, lagos)
        request(id)
        make()

        assertTrue(store.finished(id))
        assertNull(store.audio(id, 1))
        assertTrue(store.settled(id, 1))
        assertFalse(said.any { "peloton" in it })
        assertNotNull(store.audio(id, 2))
    }

    @Test
    fun stoppedPartWayItKeepsTheFinishedPagesAndCarriesOnFromTheNext() {
        inUse()
        val id = edition("Thursday", knots, lagos)
        request(id)
        // Unplugged in the middle of the second article.
        val job = Job()
        afterLine = { if (it == "Rest of World") job.cancel() }
        val stopped = runBlocking { runCatching { withContext(job) { maker.makeAll() } }.exceptionOrNull() }
        assertTrue(stopped is CancellationException)
        assertNotNull(store.audio(id, 0))
        assertNull(store.audio(id, 1))
        assertFalse(store.settled(id, 1))
        assertFalse(store.finished(id))
        assertTrue("no half-made page is left", store.dir.list()!!.none { it.endsWith(".part") })

        afterLine = {}
        said.clear()
        make()
        assertTrue(store.finished(id))
        assertEquals("Rest of World", said.first())
    }

    @Test
    fun aNewerEditionGoesFirstAndTheOlderOneCarriesOnAfter() {
        inUse()
        val older = edition("Wednesday", knots, lagos)
        val newer = edition("Thursday", lagos)
        request(older)
        afterLine = { if (it == "Counting Knots") request(newer) }
        make()

        assertTrue(store.finished(older))
        assertTrue(store.finished(newer))
        // The older edition's first article, then all of the newer one, then the rest of the older.
        val titles = said.filter { it == "Counting Knots" || it == "Lagos Drivers" }
        assertEquals(listOf("Counting Knots", "Lagos Drivers", "Lagos Drivers"), titles)
    }

    @Test
    fun aPodcastKeepsTheVoiceItStartedIn() {
        inUse(PodcastVoice.HEART)
        val first = edition("Wednesday", lagos)
        request(first)
        inUse(PodcastVoice.GEORGE)
        request(first)
        val second = edition("Thursday", lagos)
        request(second)
        make()

        assertEquals(PodcastVoice.HEART, store.voice(first))
        assertEquals(PodcastVoice.GEORGE, store.voice(second))
        // Newest first, in its voice; then the older in its own.
        assertEquals(listOf(PodcastVoice.GEORGE, PodcastVoice.HEART), voices)
        assertEquals(2, released)
    }

    @Test
    fun nothingIsAskedForWhileThePhonesVoiceReads() {
        val id = edition("Thursday", lagos)
        request(id)
        assertEquals(0, started)
        assertTrue(store.waiting().isEmpty())
    }

    @Test
    fun turningKokoroOffStopsTheMakingAndKeepsWhatsAskedFor() {
        inUse()
        val id = edition("Thursday", knots, lagos)
        request(id)
        afterLine = { if (it == "It took a week.") runBlocking { settings.update { s -> s.copy(listenVoice = ListenVoice.PHONE) } } }
        make()

        // The page under way is finished, and no more.
        assertNotNull(store.audio(id, 0))
        assertFalse(store.settled(id, 1))
        assertEquals(listOf(id), store.waiting())
    }

    @Test
    fun anEditionDeletedWhileItsPodcastIsMadeLeavesNothingBehind() {
        inUse()
        val id = edition("Thursday", knots, lagos)
        request(id)
        afterLine = { if (it == "Then a teacher counted.") runBlocking { editions.delete(id); store.delete(id) } }
        make()

        assertNull(store.voice(id))
        assertFalse(File(store.dir, id.toString()).exists())
        assertTrue(store.waiting().isEmpty())
        assertFalse(said.contains("Rest of World"))
    }

    @Test
    fun aPodcastWhoseBookIsGoneIsDropped() {
        inUse()
        val id = edition("Thursday", lagos)
        request(id)
        runBlocking { editions.pruneFiles(keep = 0) }
        make()

        assertTrue(said.isEmpty())
        assertTrue(store.waiting().isEmpty())
        assertNull(store.voice(id))
    }

    @Test
    fun aPageKokoroCantSayIsLeftToThePhonesVoice() {
        inUse()
        val id = edition("Thursday", knots, lagos)
        request(id)
        afterLine = { if (it == "Then a teacher counted.") throw IllegalStateException("bad text") }
        make()

        assertTrue(store.finished(id))
        assertNull(store.audio(id, 0))
        assertTrue(store.settled(id, 0))
        assertNotNull(store.audio(id, 1))
    }

    @Test
    fun onlyEnglishIsKokoros() {
        assertTrue(PodcastMaker.english(null))
        assertTrue(PodcastMaker.english("en-GB"))
        assertTrue(PodcastMaker.english("EN"))
        assertFalse(PodcastMaker.english("fr"))
        assertFalse(PodcastMaker.english("ja-JP"))
    }

    private companion object {
        const val RATE = 1000
    }
}
