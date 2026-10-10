package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.core.listen.SpeechCheck
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Makes podcasts in Kokoro's voice from editions' books, a page at a time, newest edition first.
 * Run by [com.app.newspaperss.work.PodcastWorker] while the phone charges: stopped (unplugged, or
 * Android's time limit), it keeps what's made and carries on from there when it runs again.
 */
class PodcastMaker(
    private val editions: EditionRepository,
    private val store: PodcastStore,
    private val install: KokoroInstall,
    private val settings: SettingsStore,
    private val engine: (PodcastVoice) -> PodcastEngine,
    private val encoder: AudioEncoder,
    /** Seconds of audio in a piece: kept that often, so a stop loses at most that much work. */
    private val pieceSeconds: Double = PIECE_SECONDS,
    private val now: () -> Long = System::nanoTime,
    private val log: PodcastLog = PodcastLog(null),
) {
    /** Makes every podcast asked for, until they're all finished, Kokoro is turned off, or it's stopped. */
    suspend fun makeAll() = withContext(Dispatchers.Default) {
        // A run stopped mid-sentence goes on in native code until the sentence ends; a new run waits
        // for it, rather than loading Kokoro twice and writing the same piece.
        val asked = now()
        // A sentence that never ends would hold every run here: this, and no "Kokoro loaded", says so.
        if (KokoroEngine.lock.isLocked) log.add("Waiting for Kokoro: the last run finishing its sentence, or the speed check")
        KokoroEngine.lock.withLock {
            val waited = seconds(now() - asked)
            if (waited >= 1) log.add("Waited ${waited}s for the last run's sentence to end")
            store.dropScratch()
            makeUnderLock()
        }
    }

    /**
     * Keeps this phone's pace from what a finished podcast cost, over all the runs it took, so
     * scheduled editions start as early as it needs. One run alone may be cut off (unplugged,
     * or Android's limits on background work), and is mostly a cool phone's.
     */
    private suspend fun learnPace(editionId: Long) {
        val (nanos, speech) = store.cost(editionId)
        if (speech < PodcastPace.LEARN_FROM_SECONDS) return
        settings.update { s ->
            val last = s.podcastPace ?: return@update s
            s.copy(podcastPace = PodcastPace.learn(last.toDouble(), s.podcastPaceMeasured, nanos / 1e9, speech).toFloat(), podcastPaceMeasured = true)
        }
    }

    private suspend fun makeUnderLock() {
        // Loaded once, and again only for a podcast in another voice: loading takes seconds.
        var kokoro: Pair<PodcastVoice, PodcastEngine>? = null
        try {
            while (true) {
                val editionId = store.waiting().firstOrNull()
                if (editionId == null) {
                    log.add("Nothing left to make")
                    break
                }
                if (!inUse()) {
                    log.add("Stopped: Kokoro turned off or removed")
                    break
                }
                store.making(editionId)
                val book = ListenBook.open(editions, editionId)
                if (book == null) {
                    // Its book is gone, so is the podcast's reason to be.
                    store.delete(editionId)
                    continue
                }
                val voice = store.voice(editionId) ?: continue
                book.use {
                    for (page in book.pages.indices) {
                        if (store.settled(editionId, page)) continue
                        if (!inUse()) {
                            log.add("Stopped: Kokoro turned off or removed")
                            return
                        }
                        // A newer edition asked for since: it goes first, and this one carries on after.
                        if (store.waiting().firstOrNull() != editionId) return@use
                        if (kokoro?.first != voice) {
                            kokoro?.second?.release()
                            kokoro = null
                            // Loading is part of what a podcast costs: each of Android's stops means another.
                            val began = now()
                            kokoro = voice to engine(voice)
                            store.addCost(editionId, now() - began, 0.0)
                            log.add("Kokoro loaded (${voice.label}) in ${seconds(now() - began)}s")
                        }
                        makePage(editionId, page, script(book, page), kokoro!!.second)
                    }
                    store.finish(editionId)
                    log.add("Edition $editionId: podcast finished")
                    // Failing to keep the pace mustn't stop the podcasts behind this one.
                    runCatching { learnPace(editionId) }
                }
            }
        } finally {
            store.making(null)
            kokoro?.second?.release()
        }
    }

    // Asked before each page, so turning Kokoro off or removing it stops the making within a page.
    private suspend fun inUse() = install.complete && settings.current().listenVoice == ListenVoice.PODCAST

    /** The page's script, or null if it can't be read: the phone's voice will make what it can of it. */
    private suspend fun script(book: ListenBook, page: Int): ListenScript? = try {
        book.script(page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // An article nested deeper than the parser's stack.
        null
    }

    private suspend fun makePage(editionId: Long, page: Int, script: ListenScript?, kokoro: PodcastEngine) {
        if (script == null || script.lines.isEmpty() || !english(script.language)) {
            store.leaveLive(editionId, page)
            return
        }
        val lines = script.lines
        store.lines(editionId, page, lines.size)
        val (deadAt, deaths) = store.deaths(editionId, page)
        val from = store.linesMade(editionId, page)
        log.add("Edition $editionId page $page: from line $from of ${lines.size}" + if (deadAt >= 0) ", line $deadAt took the app down $deaths times" else "")
        var piece: Piece? = null
        var next = from
        try {
            for (i in from until lines.size) {
                // Native code can't be stopped mid-sentence: between sentences is the soonest.
                currentCoroutineContext().ensureActive()
                val death = if (i == deadAt) deaths + 1 else 1
                if (death > CRASHES) throw Unsayable(IllegalStateException("Line $i took the app down $deaths times"))
                val began = now()
                store.saying(editionId, page, i, death)
                val speech = try {
                    kokoro.speak(lines[i].spoken)
                } catch (e: Exception) {
                    throw Unsayable(e)
                }
                val current = piece ?: Piece(i, store.scratch(editionId, page, i), speech.sampleRate).also { piece = it }
                if (i > 0 && lines[i - 1].block != lines[i].block) current.write(FloatArray((current.rate * PAUSE).toInt()))
                current.starts += current.seconds
                SpeechCheck.problems(speech.samples, speech.sampleRate, lines[i].spoken.length)?.let {
                    log.add("Page $page line $i, ${"%.1f".format(Locale.ROOT, current.seconds)}s into piece ${current.firstLine}: $it; Kokoro was given: ${SpeechCheck.exactly(lines[i].spoken)}")
                }
                current.write(speech.samples)
                current.spoken += speech.samples.size
                current.work += now() - began
                val took = seconds(now() - began)
                if (took >= SLOW_LINE_SECONDS) log.add("Page $page line $i took ${took}s (${lines[i].spoken.length} characters)")
                next = i + 1
                if (current.seconds >= pieceSeconds && i < lines.lastIndex) {
                    current.keep(editionId, page)
                    piece = null
                }
            }
            piece?.keep(editionId, page)
            piece = null
            store.complete(editionId, page)
            log.add("Page $page made")
        } catch (e: Unsayable) {
            // Something in this page Kokoro couldn't say: the phone's voice reads it, rather than
            // the podcast stopping at it every time.
            log.add("Page $page left to the phone's voice: ${e.cause?.message ?: e.cause?.javaClass?.name}")
            store.leaveLive(editionId, page)
        } catch (e: CancellationException) {
            log.add("Stopped on page $page before line $next; ${piece?.seconds?.toInt() ?: 0}s of unkept audio dropped, resumes at line ${piece?.firstLine ?: next}")
            throw e
        } catch (e: IOException) {
            // A full disk, most likely: tried again later, as it is.
            log.add("Page $page: ${e.javaClass.simpleName}, tried again next run")
            throw e
        } catch (e: Exception) {
            // The encoder, say, failing the same way each time: given up after a few runs, so
            // the pages after it, and older podcasts, aren't held up for good.
            val failures = store.failed(editionId, page)
            log.add("Page $page failed ($failures of $FAILURES): ${e.javaClass.name}")
            if (failures < FAILURES) throw e
            log.add("Page $page left to the phone's voice")
            store.leaveLive(editionId, page)
        } finally {
            piece?.drop()
            store.said(editionId, page)
        }
    }

    /** A piece being written: opened on its first line, kept once it's long enough or the page ends. */
    private inner class Piece(val firstLine: Int, val file: File, val rate: Int) {
        /** Time spent making this piece: Kokoro, the encoder and the files. */
        var work = 0L
        /** Samples of speech, not counting the breaths between paragraphs: what the check timed too. */
        var spoken = 0L
        private val sink = encoder.open(file, rate)
        val starts = mutableListOf<Double>()
        private var samples = 0L
        val seconds: Double get() = samples.toDouble() / rate

        fun write(audio: FloatArray) {
            sink.write(audio)
            samples += audio.size
        }

        fun keep(editionId: Long, page: Int) {
            // Lines Kokoro made nothing of (a row of symbols): an encoder given no audio at all
            // can't finish its file.
            if (samples == 0L) write(FloatArray(rate / 10))
            val began = now()
            sink.close()
            store.keep(editionId, page, firstLine, file, starts)
            store.addCost(editionId, work + now() - began, spoken.toDouble() / rate)
            log.add("Kept page $page lines $firstLine–${firstLine + starts.size - 1}: ${seconds.toInt()}s of audio in ${seconds(work + now() - began)}s")
        }

        fun drop() {
            runCatching { sink.close() }
            file.delete()
        }
    }

    /** Kokoro failed on a line, as opposed to the phone (a full disk, the encoder) failing around it. */
    private class Unsayable(cause: Exception) : Exception(cause)

    companion object {
        /** Seconds of quiet between paragraphs, as a reader takes a breath. */
        private const val PAUSE = 0.35

        const val PIECE_SECONDS = 120.0

        /** A line taking this long is logged: one too long for a run would never be made. */
        private const val SLOW_LINE_SECONDS = 30

        private fun seconds(nanos: Long) = nanos / 1_000_000_000

        /** Times saying the same line took the app down before the page is left to the phone's voice. */
        private const val CRASHES = 2

        /** Runs failing on the same page before it's left to the phone's voice. */
        internal const val FAILURES = 3

        /** Kokoro's voices here are English ones; a page with no language is taken to be English. */
        fun english(language: String?): Boolean = language == null || Locale.forLanguageTag(language).language == "en"
    }
}
