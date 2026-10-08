package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.testutil.FakeSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenPlayerTest {
    private class MemoryProgress : ListenProgress {
        val positions = mutableMapOf<Long, ListenPosition>()
        val done = mutableSetOf<Long>()
        override fun get(editionId: Long) = positions[editionId]
        override fun set(editionId: Long, position: ListenPosition) { positions[editionId] = position; done -= editionId }
        override fun finish(editionId: Long) { positions[editionId] = ListenPosition(); done += editionId }
        override fun finished(editionId: Long) = editionId in done
        override fun unfinished() = positions.keys.filter { it !in done }
    }

    private fun script(vararg sentences: String, language: String? = null) = ListenScript(listOf(Block.Text(Kind.PARAGRAPH, sentences.toList())), language)

    private val pages = listOf(
        script("A one.", "A two.", "A three."),
        script("B one.", "B two."),
        script("That's all for today."),
    )
    private val speaker = FakeSpeaker()
    private val progress = MemoryProgress()
    private var clock = 0L
    private var opened = 0
    private var closed = 0
    private val openBook: suspend (Long) -> ListenBook = { id ->
        opened++
        ListenBook(
            id, "Thursday Morning Edition",
            listOf(ListenPage("A", "Source A", 1.0), ListenPage("B", "Source B", 1.0), ListenPage("The end", null, 0.0, end = true)),
            read = { pages.getOrNull(it) }, onClose = { closed++ },
        )
    }
    /**
     * A chime that rings until the test says it's done. Stopped, it keeps its callback, as a
     * finish already posted would still arrive: the player must ignore it.
     */
    private class HeldChime : Chime {
        var ringing: (() -> Unit)? = null
        var stopped = false
        var rung = 0
        override fun play(done: () -> Unit) { rung++; ringing = done; stopped = false }
        override fun stop() { stopped = true }
        override fun release() = stop()
        fun end() = ringing?.also { ringing = null }?.invoke()
    }

    private val chime = HeldChime()
    private val player = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), now = { clock }, chime = chime)
    private val state get() = player.state.value

    @Test
    fun itReadsTheEditionThroughToItsEndAndStops() {
        player.start(7)
        // The line being read and the next, so there's no gap between them.
        assertEquals(listOf("A one.", "A two."), speaker.queue.map { it.text })
        val heard = mutableListOf<String>()
        while (speaker.queue.isNotEmpty() || (chime.ringing != null && !chime.stopped)) {
            if (speaker.queue.isEmpty()) chime.end() else heard += speaker.sayNext()
        }
        assertEquals(listOf("A one.", "A two.", "A three.", "B one.", "B two.", "That's all for today."), heard)
        assertTrue(state.finished)
        assertFalse(state.playing)
        assertTrue(progress.finished(7))
    }

    @Test
    fun aChimeSitsBetweenOneArticleAndTheNextAndTheNextWaitsForIt() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        speaker.sayNext() // "A three.", the article's last
        assertEquals(1, chime.rung)
        assertTrue("the next article waits for the chime", speaker.queue.isEmpty())
        assertEquals(ListenPosition(1, 0), state.at)
        chime.end()
        assertEquals("B one.", speaker.queue.first().text)
    }

    @Test
    fun skippingToTheNextArticleHasNoChime() {
        player.start(7)
        player.next()
        assertEquals("B one.", speaker.queue.first().text)
        assertEquals(0, chime.rung)
    }

    @Test
    fun pausedDuringTheChimeItHoldsAndPlayStartsTheNextArticle() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        speaker.sayNext()
        player.pause()
        assertTrue("pausing stops the chime", chime.stopped)
        chime.end() // its finish, already on its way
        assertTrue("a pause holds", speaker.queue.isEmpty())
        player.play()
        assertEquals("B one.", speaker.queue.first().text)
        assertEquals(1, chime.rung)
    }

    @Test
    fun backDuringThePauseGoesToTheEndOfTheArticleJustHeard() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        speaker.sayNext()
        clock = 10_000
        player.back()
        assertEquals("A three.", speaker.queue.first().text)
        assertEquals(ListenPosition(0, 2), state.at)
    }

    @Test
    fun forwardDuringThePauseStartsTheNextArticleFromItsFirstSentence() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        speaker.sayNext()
        player.forward()
        assertTrue(chime.stopped)
        assertEquals("B one.", speaker.queue.first().text)
    }

    @Test
    fun aSpeedChangeOrAnotherPlayDuringThePauseLetsItFinish() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        speaker.sayNext()
        player.setSpeed(1.5f)
        player.play()
        assertFalse(chime.stopped)
        assertTrue(speaker.queue.isEmpty())
        chime.end()
        assertEquals("B one.", speaker.queue.first().text)
        assertEquals(1.5f, speaker.queue.first().rate)
    }

    @Test
    fun eachLineThatStartsIsWhereItIsAndIsSaved() {
        player.start(7)
        speaker.sayNext()
        speaker.startNext()
        assertEquals(ListenPosition(0, 1), state.at)
        assertEquals(ListenPosition(0, 1), progress.get(7))
        // The next line is queued as this one starts.
        assertEquals(listOf("A two.", "A three."), speaker.queue.map { it.text })
    }

    @Test
    fun itPicksUpWhereItWasLeftAndAFinishedEditionStartsAgain() {
        progress.set(7, ListenPosition(1, 1))
        player.start(7)
        assertEquals("B two.", speaker.queue.first().text)

        player.stop()
        progress.finish(7)
        player.start(7)
        assertEquals("A one.", speaker.queue.first().text)
    }

    @Test
    fun backRestartsTheSentenceAndASecondTapGoesToTheOneBefore() {
        player.start(7)
        speaker.sayNext()
        speaker.startNext() // "A two." starts at 0
        clock = 5_000
        player.back()
        assertEquals("A two.", speaker.queue.first().text)
        speaker.startNext() // restarted at 5s
        clock = 6_000
        player.back()
        assertEquals("A one.", speaker.queue.first().text)
        assertEquals(ListenPosition(0, 0), state.at)
    }

    @Test
    fun backFromAnArticlesFirstSentenceGoesToTheEndOfTheOneBefore() {
        progress.set(7, ListenPosition(1, 0))
        player.start(7)
        speaker.startNext()
        player.back()
        assertEquals(ListenPosition(0, 2), state.at)
        assertEquals("A three.", speaker.queue.first().text)
    }

    @Test
    fun pausedBackStepsToTheSentenceBeforeWithoutPlaying() {
        progress.set(7, ListenPosition(0, 2))
        player.start(7)
        player.pause()
        player.back()
        assertEquals(ListenPosition(0, 1), state.at)
        assertTrue(speaker.queue.isEmpty())
        player.play()
        assertEquals("A two.", speaker.queue.first().text)
    }

    @Test
    fun forwardAndNextMoveBySentenceAndByArticle() {
        player.start(7)
        player.forward()
        assertEquals("A two.", speaker.queue.first().text)
        player.next()
        assertEquals("B one.", speaker.queue.first().text)
        player.forward()
        player.forward()
        assertEquals(ListenPosition(2, 0), state.at)
    }

    @Test
    fun previousGoesBackAnArticleNearItsStartAndRestartsItFurtherIn() {
        progress.set(7, ListenPosition(1, 1))
        player.start(7)
        player.previous()
        assertEquals(ListenPosition(0, 0), state.at)

        val long = ListenPlayer(
            speaker, progress,
            open = { ListenBook(it, "E", listOf(ListenPage("A", null, 1.0), ListenPage("B", null, 1.0)), read = { script("1.", "2.", "3.", "4.", "5.") }) },
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        progress.set(8, ListenPosition(1, 4))
        long.start(8)
        long.previous()
        assertEquals(ListenPosition(1, 0), long.state.value.at)
    }

    @Test
    fun aLineFromBeforeAPauseOrAJumpCantMoveIt() {
        player.start(7)
        val stale = speaker.queue.first().id
        player.seek(ListenPosition(1, 0))
        speaker.listener?.onStart(stale)
        speaker.listener?.onDone(stale)
        assertEquals(ListenPosition(1, 0), state.at)
    }

    @Test
    fun pausingStopsTheVoiceAndPlayCarriesOn() {
        player.start(7)
        speaker.sayNext()
        speaker.startNext()
        player.pause()
        assertTrue(speaker.queue.isEmpty())
        assertFalse(state.playing)
        player.toggle()
        assertTrue(state.playing)
        assertEquals("A two.", speaker.queue.first().text)
    }

    @Test
    fun aNewSpeedTakesEffectFromTheSentenceBeingRead() {
        player.start(7)
        speaker.startNext()
        player.setSpeed(1.5f)
        assertEquals(1.5f, speaker.queue.first().rate)
        assertEquals("A one.", speaker.queue.first().text)
    }

    @Test
    fun theSpeedIsKeptInSettingsAndFollowedFromThere() {
        val saved = MutableStateFlow(1.2f)
        val kept = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), savedSpeed = saved, saveSpeed = { saved.value = it })
        kept.start(7)
        assertEquals(1.2f, speaker.queue.first().rate)

        kept.setSpeed(1.5f)
        assertEquals(1.5f, saved.value)

        // Changed in Settings while it plays: from the sentence being read.
        speaker.startNext()
        saved.value = 0.8f
        assertEquals(0.8f, kept.state.value.speed)
        assertEquals("A one." to 0.8f, speaker.queue.first().let { it.text to it.rate })
    }

    @Test
    fun aSpeedStillBeingSavedIsntUndoneByTheOneBeforeIt() {
        val saved = MutableSharedFlow<Float>(replay = 1).apply { tryEmit(1f) }
        val saving = CompletableDeferred<Unit>()
        val kept = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), savedSpeed = saved, saveSpeed = { saving.await(); saved.emit(it) })
        kept.start(7)
        kept.setSpeed(1.5f)
        // The store hands back what it had before the save landed.
        saved.tryEmit(1f)
        assertEquals(1.5f, kept.state.value.speed)
        assertEquals(1.5f, speaker.queue.first().rate)
        saving.complete(Unit)
        assertEquals(1.5f, kept.state.value.speed)
    }

    @Test
    fun aChangeInSettingsWhileTheButtonsSpeedSavesIsCaughtUpWith() {
        val saved = MutableStateFlow(1f)
        val saving = CompletableDeferred<Unit>()
        // The button's 1.2 is written, and Settings writes 1.5 before the save returns.
        val kept = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), savedSpeed = saved, saveSpeed = { saved.value = it; saving.await() })
        kept.start(7)
        kept.setSpeed(1.2f)
        saved.value = 1.5f
        assertEquals(1.2f, kept.state.value.speed)
        saving.complete(Unit)
        assertEquals(1.5f, kept.state.value.speed)
    }

    @Test
    fun aSpeedACarAsksForIsTheNearestListedOne() {
        val saved = MutableStateFlow(1f)
        val kept = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), savedSpeed = saved, saveSpeed = { saved.value = it })
        kept.setSpeed(2f)
        assertEquals(1.5f, saved.value)
        kept.setSpeed(0.5f)
        assertEquals(0.8f, saved.value)
    }

    @Test
    fun aSpeedThatCantBeSavedGoesBackToTheKeptOne() {
        val saved = MutableStateFlow(1.2f)
        val kept = ListenPlayer(speaker, progress, open = openBook, scope = TestScope(UnconfinedTestDispatcher()), savedSpeed = saved, saveSpeed = { throw java.io.IOException("disk full") })
        kept.setSpeed(1.5f)
        assertEquals(1.2f, kept.state.value.speed)
    }

    @Test
    fun theSampleIsSaidAtItsSpeedAndPausesTheEdition() {
        player.start(7)
        speaker.startNext()
        player.sample("This is how it sounds.", 1.5f)
        assertFalse(state.playing)
        assertEquals(listOf("This is how it sounds." to 1.5f), speaker.queue.map { it.text to it.rate })
        // Its start and end move nothing.
        speaker.sayNext()
        assertEquals(ListenPosition(0, 0), state.at)
        assertFalse(state.playing)
    }

    @Test
    fun aLineTheVoiceCantSayIsPassedOverButThreeInARowStopIt() {
        player.start(7)
        speaker.fail()
        assertTrue(state.playing)
        assertNull(state.error)
        assertEquals("A two.", speaker.queue.first().text)
        speaker.fail()
        speaker.fail()
        assertFalse(state.playing)
        assertTrue(state.error!!.contains("voice"))
    }

    @Test
    fun anArticleInALanguageWithNoVoiceSaysSo() {
        val french = ListenPlayer(
            speaker, progress,
            open = { ListenBook(it, "E", listOf(ListenPage("Bonjour", "Le Monde", 1.0)), read = { script("Bonjour.", language = "fr") }) },
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        speaker.installed = setOf("en")
        french.start(9)
        assertEquals("fr", french.state.value.missingLanguage)
        assertEquals("fr", speaker.queue.first().language)
    }

    @Test
    fun startingAnotherEditionLetsTheFirstGoAndAGoneEditionSaysWhy() {
        player.start(7)
        player.start(8)
        assertEquals(1, closed)
        assertEquals(8L, state.editionId)

        val gone = ListenPlayer(speaker, progress, open = { null }, scope = TestScope(UnconfinedTestDispatcher()))
        gone.start(3)
        assertTrue(gone.state.value.error!!.contains("gone"))
        gone.play()
        assertFalse(gone.state.value.playing)
    }

    @Test
    fun startingTheSameEditionAgainCarriesOnRatherThanReopeningIt() {
        player.start(7)
        player.pause()
        player.start(7)
        assertEquals(1, opened)
        assertTrue(state.playing)
    }

    @Test
    fun aPageWithNothingToSayIsPassedOverButOneThatCantBeReadStops() {
        val holes = ListenPlayer(
            speaker, progress,
            open = { ListenBook(it, "E", listOf(ListenPage("A", null, 1.0), ListenPage("B", null, 1.0), ListenPage("C", null, 1.0)), read = { i ->
                when (i) { 0 -> ListenScript(emptyList()); 1 -> script("B one."); else -> null }
            }) },
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        holes.start(5)
        assertEquals(ListenPosition(1, 0), holes.state.value.at)
        assertEquals("B one.", speaker.queue.first().text)

        // The file gone part way: it says so and keeps the place, not "heard to the end".
        speaker.sayNext()
        assertFalse(holes.state.value.playing)
        assertFalse(holes.state.value.finished)
        assertTrue(holes.state.value.error!!.contains("can't be read"))
        assertFalse(progress.finished(5))
        assertEquals(ListenPosition(1, 0), progress.get(5))
    }

    @Test
    fun controlsTappedWhileTheNextArticleIsReadWaitForIt() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = ListenPlayer(
            speaker, progress,
            open = { ListenBook(it, "E", listOf(ListenPage("A", null, 1.0), ListenPage("B", null, 1.0)), read = { i ->
                if (i == 1) gate.await()
                if (i == 0) script("A one.", "A two.") else script("B one.", "B two.")
            }) },
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        slow.start(6)
        slow.next()
        slow.pause()
        slow.play()
        slow.forward()
        slow.back()
        slow.setSpeed(1.5f)
        // Nothing of the page being left is said meanwhile.
        assertTrue(speaker.queue.isEmpty())
        gate.complete(Unit)
        assertEquals(ListenPosition(1, 0), slow.state.value.at)
        assertEquals("B one.", speaker.queue.first().text)
        assertEquals(1.5f, speaker.queue.first().rate)
    }

    @Test
    fun releasingStopsAndLetsTheVoiceGo() {
        player.start(7)
        player.release()
        assertNull(state.editionId)
        assertTrue(speaker.released)
    }

    @Test
    fun howFarInCountsTheArticlesBeforeAndTheSentencesHeard() {
        progress.set(7, ListenPosition(1, 1))
        player.start(7)
        assertTrue(state.secondsIn > 60.0)
        assertEquals(120.0, state.secondsTotal, 0.01)
    }

    @Test
    fun aDeletedBookIsLetGoButAnotherEditionsIsnt() {
        player.start(7)
        player.forget(8)
        assertEquals(7L, state.editionId)
        player.forget(7)
        assertNull(state.editionId)
        assertEquals(1, closed)
        assertTrue(speaker.queue.isEmpty())
    }

    @Test
    fun aTurnCancelledBeforeItRanDoesntLeaveTheControlsDead() {
        // A scope that queues work rather than running it at once, as the main thread does.
        val queued = TestScope(kotlinx.coroutines.test.StandardTestDispatcher())
        val p = ListenPlayer(
            speaker, progress,
            open = { ListenBook(it, "E", listOf(ListenPage("A", null, 1.0), ListenPage("B", null, 1.0)), read = { i -> script(if (i == 0) "A one." else "B one.") }) },
            scope = queued,
        )
        p.start(4)
        queued.testScheduler.runCurrent()
        p.next() // queued, not run
        p.start(5) // cancels it
        queued.testScheduler.runCurrent()
        p.pause()
        p.play()
        assertEquals("A one.", speaker.queue.first().text)
    }
}
