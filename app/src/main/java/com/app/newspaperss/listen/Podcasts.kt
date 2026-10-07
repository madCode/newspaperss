package com.app.newspaperss.listen

import android.util.Log
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.app.newspaperss.core.listen.PodcastPace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Podcasts made ahead, a folder per edition. Each page's audio is kept in pieces of a couple of
 * minutes, each with the time its lines start, so work stopped part way through a long article
 * (unplugged, or Android's 10-minute limit on work) carries on from the last piece kept. A piece
 * is there whole or not at all.
 *
 * A folder is made only when a podcast is asked for, and once deleted (with its edition, or with
 * Kokoro) nothing writes into it again: a piece finishing after that is dropped.
 */
class PodcastStore(val dir: File) {
    private fun folder(editionId: Long) = File(dir, editionId.toString())

    private fun voiceFile(editionId: Long) = File(folder(editionId), VOICE)

    /** Asks for [editionId]'s podcast in [voice]. Asked again, it keeps the voice it was started in. */
    @Synchronized
    fun want(editionId: Long, voice: PodcastVoice) {
        folder(editionId).mkdirs()
        // Rewritten if unreadable: the app may have died as it was first written.
        if (voice(editionId) == null) voiceFile(editionId).writeText(voice.name)
    }

    /** Editions whose podcast was asked for and isn't finished, newest first. */
    fun waiting(): List<Long> = (dir.listFiles() ?: emptyArray())
        .mapNotNull { it.name.toLongOrNull() }
        .filter { voice(it) != null && !finished(it) }
        .sortedDescending()

    fun voice(editionId: Long): PodcastVoice? =
        voiceFile(editionId).takeIf { it.exists() }?.readText()?.let { name -> PodcastVoice.entries.firstOrNull { it.name == name } }

    /** [page]'s audio made so far, in order. */
    fun pieces(editionId: Long, page: Int): List<PodcastPiece> {
        val prefix = "$page$SEP"
        return (folder(editionId).listFiles() ?: emptyArray())
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".$AUDIO") }
            .mapNotNull { audio ->
                val first = audio.name.removePrefix(prefix).removeSuffix(".$AUDIO").toIntOrNull() ?: return@mapNotNull null
                val starts = File(audio.parentFile, audio.name.removeSuffix(AUDIO) + STARTS).takeIf { it.exists() }
                    ?.readLines()?.mapNotNull { it.toDoubleOrNull() } ?: return@mapNotNull null
                PodcastPiece(audio, first, starts)
            }
            .sortedBy { it.firstLine }
    }

    /** How many of [page]'s lines are made: where making it carries on from. */
    fun linesMade(editionId: Long, page: Int): Int = pieces(editionId, page).lastOrNull()?.let { it.firstLine + it.starts.size } ?: 0

    /** Whether all of [page] is made. */
    fun made(editionId: Long, page: Int): Boolean = File(folder(editionId), "$page.$MADE").exists()

    /** Whether [page] is left to the phone's voice: not English, or something Kokoro couldn't say. */
    fun live(editionId: Long, page: Int): Boolean = File(folder(editionId), "$page.$LIVE").exists()

    fun settled(editionId: Long, page: Int): Boolean = made(editionId, page) || live(editionId, page)

    fun finished(editionId: Long): Boolean = File(folder(editionId), FINISHED).exists()

    /**
     * Keeps a piece of [page] from [firstLine], written to [made], with its lines' [starts]. The
     * audio is renamed into place last, so a piece with audio always has its times.
     */
    @Synchronized
    fun keep(editionId: Long, page: Int, firstLine: Int, made: File, starts: List<Double>) {
        val folder = folder(editionId)
        if (!folder.exists()) {
            made.delete()
            return
        }
        val name = "$page$SEP$firstLine"
        File(folder, "$name.$STARTS").writeText(starts.joinToString("\n", postfix = "\n") { "%.3f".format(Locale.ROOT, it) })
        if (!made.renameTo(File(folder, "$name.$AUDIO"))) throw IOException("Couldn't keep $name")
    }

    @Synchronized
    fun complete(editionId: Long, page: Int) = mark(editionId, "$page.$MADE")

    /** Leaves [page] to the phone's voice, dropping any of it made: an article doesn't change voice part way. */
    @Synchronized
    fun leaveLive(editionId: Long, page: Int) {
        folder(editionId).listFiles { file -> file.name.startsWith("$page$SEP") }?.forEach { it.delete() }
        mark(editionId, "$page.$LIVE")
    }

    @Synchronized
    fun finish(editionId: Long) = mark(editionId, FINISHED)

    /** Adds to what making [editionId]'s podcast has cost: time spent, and seconds of speech made. */
    @Synchronized
    fun addCost(editionId: Long, nanos: Long, speechSeconds: Double) {
        val folder = folder(editionId).takeIf { it.exists() } ?: return
        val (n, s) = cost(editionId)
        File(folder, COST).writeText("${n + nanos} ${"%.3f".format(Locale.ROOT, s + speechSeconds)}")
    }

    /** What making [editionId]'s podcast has cost so far, over every run: nanoseconds, and seconds of speech. */
    fun cost(editionId: Long): Pair<Long, Double> =
        File(folder(editionId), COST).takeIf { it.exists() }?.readText()?.split(' ')
            ?.let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1)?.toDoubleOrNull() ?: 0.0) } ?: (0L to 0.0)

    private fun mark(editionId: Long, name: String) {
        folder(editionId).takeIf { it.exists() }?.let { File(it, name).createNewFile() }
    }

    /**
     * The line of [page] the app died saying, and how many tries in a row it has: (-1, 0) if none.
     * Only the app dying leaves this behind; see [saying].
     */
    @Synchronized
    fun deaths(editionId: Long, page: Int): Pair<Int, Int> =
        File(folder(editionId), "$page.$SAYING").takeIf { it.exists() }?.readText()?.split(' ')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (-1 to 0)

    /**
     * Notes that [line] is being said, and that if the app dies saying it, it's the [death]th time
     * in a row. Kokoro's native code can take the app down, and a line that does would otherwise
     * do it on every charge. [said] clears it.
     */
    @Synchronized
    fun saying(editionId: Long, page: Int, line: Int, death: Int) {
        folder(editionId).takeIf { it.exists() }?.let { File(it, "$page.$SAYING").writeText("$line $death") }
    }

    @Synchronized
    fun said(editionId: Long, page: Int) {
        File(folder(editionId), "$page.$SAYING").delete()
    }

    /** Counts a run that failed on [page] for some other reason than a full disk, and returns how many have. */
    @Synchronized
    fun failed(editionId: Long, page: Int): Int {
        val file = File(folder(editionId), "$page.$FAILED")
        val count = (file.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0) + 1
        if (folder(editionId).exists()) file.writeText(count.toString())
        return count
    }

    /** Somewhere to write a piece before it's kept: inside [dir], so keeping it is a rename. */
    fun scratch(editionId: Long, page: Int, firstLine: Int): File {
        dir.mkdirs()
        return File(dir, "$editionId$SEP$page$SEP$firstLine.$PART")
    }

    /** Pieces left half-written by an app that died: only called with nothing being made. */
    fun dropScratch() {
        dir.listFiles { file -> file.name.endsWith(".$PART") }?.forEach { it.delete() }
    }

    @Synchronized
    fun delete(editionId: Long) {
        folder(editionId).deleteRecursively()
    }

    @Synchronized
    fun deleteAll() {
        dir.deleteRecursively()
    }

    private companion object {
        const val SEP = "-"
        const val VOICE = "voice"
        const val FINISHED = "finished"
        const val AUDIO = "m4a"
        const val STARTS = "starts"
        const val MADE = "made"
        const val LIVE = "live"
        const val SAYING = "saying"
        const val FAILED = "failed"
        const val COST = "cost"
        const val PART = "part"
    }
}

/** Part of a page's audio, from its [firstLine]: when each of its lines starts, in seconds into [audio]. */
data class PodcastPiece(val audio: File, val firstLine: Int, val starts: List<Double>)

/** Where encoded audio goes, a block of samples at a time. */
interface AudioSink : Closeable {
    fun write(samples: FloatArray)
}

/** Opens an [AudioSink] writing mono audio at [sampleRate] to [file]. */
fun interface AudioEncoder {
    fun open(file: File, sampleRate: Int): AudioSink
}

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
    private val start: () -> Unit,
) {
    /**
     * Asks for [editionId]'s podcast if Kokoro is in use, and starts making it once the phone
     * charges. The voice is the one chosen now: a podcast keeps the voice it started in.
     */
    suspend fun request(editionId: Long) {
        val s = settings.current()
        if (s.listenVoice != ListenVoice.PODCAST || !withContext(Dispatchers.IO) { install.complete }) return
        withContext(Dispatchers.IO) { store.want(editionId, s.podcastVoice) }
        start()
    }

    /** Makes every podcast asked for, until they're all finished, Kokoro is turned off, or it's stopped. */
    suspend fun makeAll() = withContext(Dispatchers.Default) {
        // A run stopped mid-sentence goes on in native code until the sentence ends; a new run waits
        // for it, rather than loading Kokoro twice and writing the same piece.
        KokoroEngine.lock.withLock {
            store.dropScratch()
            makeUnderLock()
        }
    }

    /**
     * Keeps this phone's pace from what a finished podcast cost, over all the runs it took, so
     * scheduled editions start as early as it needs. One run alone is cut off by Android's
     * 10-minute limit, and is mostly a cool phone's.
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
                val editionId = store.waiting().firstOrNull() ?: break
                if (!inUse()) break
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
                        if (!inUse()) return
                        // A newer edition asked for since: it goes first, and this one carries on after.
                        if (store.waiting().firstOrNull() != editionId) return@use
                        if (kokoro?.first != voice) {
                            kokoro?.second?.release()
                            kokoro = null
                            // Loading is part of what a podcast costs: each of Android's stops means another.
                            val began = now()
                            kokoro = voice to engine(voice)
                            store.addCost(editionId, now() - began, 0.0)
                        }
                        makePage(editionId, page, script(book, page), kokoro!!.second)
                    }
                    store.finish(editionId)
                    // Failing to keep the pace mustn't stop the podcasts behind this one.
                    runCatching { learnPace(editionId) }
                }
            }
        } finally {
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
        val (deadAt, deaths) = store.deaths(editionId, page)
        var piece: Piece? = null
        try {
            for (i in store.linesMade(editionId, page) until lines.size) {
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
                current.write(speech.samples)
                current.spoken += speech.samples.size
                current.work += now() - began
                if (current.seconds >= pieceSeconds && i < lines.lastIndex) {
                    current.keep(editionId, page)
                    piece = null
                }
            }
            piece?.keep(editionId, page)
            piece = null
            store.complete(editionId, page)
        } catch (e: Unsayable) {
            // Something in this page Kokoro couldn't say: the phone's voice reads it, rather than
            // the podcast stopping at it every time.
            Log.w(TAG, "Page $page left to the phone's voice: ${e.cause?.message ?: e.cause?.javaClass?.name}")
            store.leaveLive(editionId, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A full disk, most likely: tried again later, as it is.
            throw e
        } catch (e: Exception) {
            // The encoder, say, failing the same way each time: given up after a few runs, so
            // the pages after it, and older podcasts, aren't held up for good.
            if (store.failed(editionId, page) < FAILURES) throw e
            Log.w(TAG, "Page $page left to the phone's voice: ${e.javaClass.name}")
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
        }

        fun drop() {
            runCatching { sink.close() }
            file.delete()
        }
    }

    /** Kokoro failed on a line, as opposed to the phone (a full disk, the encoder) failing around it. */
    private class Unsayable(cause: Exception) : Exception(cause)

    companion object {
        private const val TAG = "PodcastMaker"

        /** Seconds of quiet between paragraphs, as a reader takes a breath. */
        private const val PAUSE = 0.35

        const val PIECE_SECONDS = 120.0

        /** Times saying the same line took the app down before the page is left to the phone's voice. */
        internal const val CRASHES = 2

        /** Runs failing on the same page before it's left to the phone's voice. */
        internal const val FAILURES = 3

        /** Kokoro's voices here are English ones; a page with no language is taken to be English. */
        fun english(language: String?): Boolean = language == null || Locale.forLanguageTag(language).language == "en"
    }
}
