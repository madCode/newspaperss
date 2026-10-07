package com.app.newspaperss.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.listen.KokoroInstall
import com.app.newspaperss.listen.PodcastStore
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.listen.PodcastLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Under Listen on the edition page: how much of the podcast is made, or making one. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastLineTest {
    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }
    private val store by lazy { PodcastStore(tmp.newFolder("podcasts")) }
    private val install by lazy {
        KokoroInstall(tmp.newFolder("kokoro")) { listOf(KokoroFile("tokens.txt", 6, "ce013625030ba8dba906f756967f9e9ca394464a")) }.also {
            it.file("tokens.txt").writeText("hello\n")
            it.markVerified("tokens.txt")
        }
    }
    private val asked = mutableListOf<Long>()
    private val podcasts by lazy { Podcasts(store, settings, install) { asked += it; store.want(it, PodcastVoice.HEART) } }

    /** Three articles: 8, 6 and 4 minutes to read. */
    private val minutes = listOf(8.0, 6.0, 4.0)

    private fun show(voice: ListenVoice = ListenVoice.PODCAST) {
        runBlocking { settings.update { it.copy(listenVoice = voice, podcastPace = 1.2f) } }
        compose.setContent { PodcastLine(podcasts, 7, minutes) }
    }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun anEditionMadeByHandOffersToMakeItsPodcast() {
        show()
        waitFor("No podcast for this edition yet")
        compose.onNodeWithText("Make the podcast", substring = true).performClick()
        idleUntil { asked.isNotEmpty() }
        assertEquals(listOf(7L), asked)
        // Asked for, it says how it's made.
        waitFor("Waiting to make the podcast")
    }

    @Test
    fun partMadeItSaysHowMuch() {
        store.want(7, PodcastVoice.HEART)
        store.complete(7, 0)
        show()
        // The first article, 8 minutes to read, is 12 to hear, of 27.
        waitFor("12 min of 27 min made")
        // Then made as it's being looked at.
        store.complete(7, 1)
        store.complete(7, 2)
        store.finish(7)
        waitFor("The podcast is ready.")
    }

    @Test
    fun whileItsBeingMadeItSaysSoAndTheArticleFillsAsPiecesAreKept() {
        store.want(7, PodcastVoice.HEART)
        show()
        waitFor("Waiting to make the podcast")
        // The phone charges: the first article, of four lines, has its first two made.
        store.making(7)
        waitFor("Making the podcast now")
        store.lines(7, 0, 4)
        val scratch = store.scratch(7, 0, 0).apply { writeText("audio") }
        store.keep(7, 0, 0, scratch, listOf(0.0, 2.0))
        // Half of its 12 minutes to hear.
        waitFor("6 min of 27 min made.")
        // Unplugged: it says it carries on later.
        store.making(null)
        waitFor("It carries on while the phone charges")
    }

    @Test
    fun articlesLeftOutAreSaidToBe() {
        store.want(7, PodcastVoice.HEART)
        store.complete(7, 0)
        store.leaveLive(7, 1)
        show()
        waitFor("articles it left out always do")
        store.complete(7, 2)
        store.finish(7)
        waitFor("Articles it left out")
    }

    @Test
    fun anEditionAllLeftOutIsntCalledReady() {
        store.want(7, PodcastVoice.HEART)
        (0..2).forEach { store.leaveLive(7, it) }
        store.finish(7)
        show()
        waitFor("couldn't make any of this edition")
    }

    @Test
    fun withThePhonesVoiceChosenThereIsNothing() {
        store.want(7, PodcastVoice.HEART)
        show(ListenVoice.PHONE)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("podcast", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }
}
