package com.app.newspaperss.listen

import android.util.Log
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Podcasts made ahead, a folder per edition: each page's audio, and the time each of its lines
 * starts. A page is there whole or not at all, so a podcast stopped part way plays what's made.
 *
 * A folder is made only when a podcast is asked for, and once deleted (with its edition, or with
 * Kokoro) nothing writes into it again: a page finishing after that is dropped.
 */
class PodcastStore(val dir: File) {
    private fun folder(editionId: Long) = File(dir, editionId.toString())

    private fun voiceFile(editionId: Long) = File(folder(editionId), VOICE)

    /** Asks for [editionId]'s podcast in [voice]. Asked again, it keeps the voice it was started in. */
    @Synchronized
    fun want(editionId: Long, voice: PodcastVoice) {
        folder(editionId).mkdirs()
        val file = voiceFile(editionId)
        if (!file.exists()) file.writeText(voice.name)
    }

    /** Editions whose podcast was asked for and isn't finished, newest first. */
    fun waiting(): List<Long> = (dir.listFiles() ?: emptyArray())
        .mapNotNull { it.name.toLongOrNull() }
        .filter { voice(it) != null && !finished(it) }
        .sortedDescending()

    fun voice(editionId: Long): PodcastVoice? =
        voiceFile(editionId).takeIf { it.exists() }?.readText()?.let { name -> PodcastVoice.entries.firstOrNull { it.name == name } }

    /** The audio of [page], or null if it isn't made (yet, or it's one the phone's voice reads). */
    fun audio(editionId: Long, page: Int): File? = File(folder(editionId), "$page.$AUDIO").takeIf { it.exists() }

    /** When each of [page]'s lines starts in its audio, in seconds. */
    fun starts(editionId: Long, page: Int): List<Double>? =
        File(folder(editionId), "$page.$STARTS").takeIf { it.exists() }?.readLines()?.mapNotNull { it.toDoubleOrNull() }

    /** Whether [page] is settled: made, or left to the phone's voice. */
    fun settled(editionId: Long, page: Int): Boolean =
        audio(editionId, page) != null || File(folder(editionId), "$page.$LIVE").exists()

    fun finished(editionId: Long): Boolean = File(folder(editionId), FINISHED).exists()

    /**
     * Keeps [page]'s audio, written to [made], with its lines' [starts]. The audio is renamed into
     * place last, so a page with audio always has its times.
     */
    @Synchronized
    fun keep(editionId: Long, page: Int, made: File, starts: List<Double>) {
        val folder = folder(editionId)
        if (!folder.exists()) {
            made.delete()
            return
        }
        File(folder, "$page.$STARTS").writeText(starts.joinToString("\n", postfix = "\n") { "%.3f".format(Locale.ROOT, it) })
        if (!made.renameTo(File(folder, "$page.$AUDIO"))) made.delete()
    }

    /** [page] is left to the phone's voice: not English, or nothing Kokoro could say. */
    @Synchronized
    fun leaveLive(editionId: Long, page: Int) {
        folder(editionId).takeIf { it.exists() }?.let { File(it, "$page.$LIVE").createNewFile() }
    }

    @Synchronized
    fun finish(editionId: Long) {
        folder(editionId).takeIf { it.exists() }?.let { File(it, FINISHED).createNewFile() }
    }

    /** Somewhere to write a page's audio before it's kept: inside [dir], so keeping it is a rename. */
    fun scratch(editionId: Long, page: Int): File {
        dir.mkdirs()
        return File(dir, "$editionId-$page.$PART")
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
        const val VOICE = "voice"
        const val FINISHED = "finished"
        const val AUDIO = "m4a"
        const val STARTS = "starts"
        const val LIVE = "live"
        const val PART = "part"
    }
}

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
 * Run by [com.app.newspaperss.work.PodcastWorker] while the phone charges: stopped (unplugged),
 * it keeps the pages made and carries on from the next when it runs again.
 */
class PodcastMaker(
    private val editions: EditionRepository,
    private val store: PodcastStore,
    private val install: KokoroInstall,
    private val settings: SettingsStore,
    private val engine: (PodcastVoice) -> PodcastEngine,
    private val encoder: AudioEncoder,
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
                        if (!inUse()) return@withContext
                        // A newer edition asked for since: it goes first, and this one carries on after.
                        if (store.waiting().firstOrNull() != editionId) return@use
                        if (kokoro?.first != voice) {
                            kokoro?.second?.release()
                            kokoro = null
                            kokoro = voice to engine(voice)
                        }
                        makePage(editionId, page, book.script(page), kokoro!!.second)
                    }
                    store.finish(editionId)
                }
            }
        } finally {
            kokoro?.second?.release()
        }
    }

    // Asked before each page, so turning Kokoro off or removing it stops the making within a page.
    private suspend fun inUse() = install.complete && settings.current().listenVoice == ListenVoice.PODCAST

    private suspend fun makePage(editionId: Long, page: Int, script: ListenScript?, kokoro: PodcastEngine) {
        if (script == null || script.lines.isEmpty() || !english(script.language)) {
            store.leaveLive(editionId, page)
            return
        }
        val scratch = store.scratch(editionId, page)
        var sink: AudioSink? = null
        val starts = mutableListOf<Double>()
        var samples = 0L
        var rate = 0
        try {
            var block = -1
            for (line in script.lines) {
                // Native code can't be stopped mid-sentence: between sentences is the soonest.
                currentCoroutineContext().ensureActive()
                if (block >= 0 && line.block != block && rate > 0) {
                    val pause = FloatArray((rate * PAUSE).toInt())
                    sink?.write(pause)
                    samples += pause.size
                }
                block = line.block
                starts += if (rate > 0) samples.toDouble() / rate else 0.0
                val speech = kokoro.speak(line.spoken)
                if (sink == null) {
                    rate = speech.sampleRate
                    sink = encoder.open(scratch, rate)
                }
                sink.write(speech.samples)
                samples += speech.samples.size
            }
            sink?.close()
            sink = null
            currentCoroutineContext().ensureActive()
            store.keep(editionId, page, scratch, starts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Something in this page Kokoro or the encoder couldn't handle: the phone's voice reads
            // it, rather than the podcast stopping at it every time.
            Log.w(TAG, "Page $page left to the phone's voice: ${e.javaClass.name}")
            store.leaveLive(editionId, page)
        } finally {
            runCatching { sink?.close() }
            scratch.delete()
        }
    }

    companion object {
        private const val TAG = "PodcastMaker"

        /** Seconds of quiet between paragraphs, as a reader takes a breath. */
        private const val PAUSE = 0.35

        /** Kokoro's voices here are English ones; a page with no language is taken to be English. */
        fun english(language: String?): Boolean = language == null || Locale.forLanguageTag(language).language == "en"
    }
}
