package com.app.newspaperss.listen

import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Podcasts made ahead, a folder per edition. Each page's audio is kept in pieces of a couple of
 * minutes, each with the time its lines start, so work stopped part way through a long article
 * (unplugged, or Android's 10-minute limit on work) carries on from the last piece kept. A piece
 * is there whole or not at all.
 *
 * A folder is made only when a podcast is asked for, and once deleted (with its edition, or with
 * Kokoro) nothing writes into it again: a piece finishing after that is dropped. [write] is where
 * that's kept, and [keep] for the audio.
 */
class PodcastStore(val dir: File) {
    private val _changes = MutableStateFlow(0)

    /** Moves on whenever a podcast is asked for, gains a page, is finished or deleted: for screens to follow. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    private val _making = MutableStateFlow<Long?>(null)

    /** The edition whose podcast is being made right now, in this process; null when none is. */
    val making: StateFlow<Long?> = _making.asStateFlow()

    fun making(editionId: Long?) {
        _making.value = editionId
    }

    /** Notes how many lines [page] has, so how far it's made can be shown. */
    @Synchronized
    fun lines(editionId: Long, page: Int, lines: Int) {
        write(editionId, "$page.$LINES", lines.toString())
        // Pieces kept before the count was known show from now, not from the next piece.
        changed()
    }

    /** How much of [page] is made, 0 to 1: whole once it's made, by its lines while it's being made. */
    fun share(editionId: Long, page: Int): Double {
        if (made(editionId, page)) return 1.0
        val lines = read(editionId, "$page.$LINES")?.toIntOrNull()?.takeIf { it > 0 } ?: return 0.0
        return (linesMade(editionId, page).toDouble() / lines).coerceIn(0.0, 1.0)
    }

    private fun folder(editionId: Long) = File(dir, editionId.toString())

    /** Asks for [editionId]'s podcast in [voice]. Asked again, it keeps the voice it was started in. */
    @Synchronized
    fun want(editionId: Long, voice: PodcastVoice) {
        folder(editionId).mkdirs()
        // Rewritten if unreadable: the app may have died as it was first written.
        if (voice(editionId) == null) write(editionId, VOICE, voice.name)
        changed()
    }

    /** Editions whose podcast was asked for and isn't finished, newest first. */
    fun waiting(): List<Long> = (dir.listFiles() ?: emptyArray())
        .mapNotNull { it.name.toLongOrNull() }
        .filter { voice(it) != null && !finished(it) }
        .sortedDescending()

    fun voice(editionId: Long): PodcastVoice? =
        read(editionId, VOICE)?.let { name -> PodcastVoice.entries.firstOrNull { it.name == name } }

    /** [page]'s audio made so far, in order. */
    fun pieces(editionId: Long, page: Int): List<PodcastPiece> {
        val prefix = "$page$SEP"
        return (folder(editionId).listFiles() ?: emptyArray())
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".$AUDIO") }
            .mapNotNull { audio ->
                val first = audio.name.removePrefix(prefix).removeSuffix(".$AUDIO").toIntOrNull() ?: return@mapNotNull null
                val starts = read(editionId, audio.name.removeSuffix(AUDIO) + STARTS)
                    ?.lines()?.mapNotNull { it.toDoubleOrNull() } ?: return@mapNotNull null
                PodcastPiece(audio, first, starts)
            }
            .sortedBy { it.firstLine }
    }

    /** How many of [page]'s lines are made: where making it carries on from. */
    fun linesMade(editionId: Long, page: Int): Int = pieces(editionId, page).lastOrNull()?.let { it.firstLine + it.starts.size } ?: 0

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
        write(editionId, "$name.$STARTS", starts.joinToString("\n", postfix = "\n") { "%.3f".format(Locale.ROOT, it) })
        if (!made.renameTo(File(folder, "$name.$AUDIO"))) throw IOException("Couldn't keep $name")
        changed()
    }

    @Synchronized
    fun complete(editionId: Long, page: Int) {
        write(editionId, "$page.$MADE", "")
        changed()
    }

    /** Leaves [page] to the phone's voice, dropping any of it made: an article doesn't change voice part way. */
    @Synchronized
    fun leaveLive(editionId: Long, page: Int) {
        folder(editionId).listFiles { file -> file.name.startsWith("$page$SEP") }?.forEach { it.delete() }
        write(editionId, "$page.$LIVE", "")
        changed()
    }

    @Synchronized
    fun finish(editionId: Long) {
        write(editionId, FINISHED, "")
        changed()
    }

    /** Adds to what making [editionId]'s podcast has cost: time spent, and seconds of speech made. */
    @Synchronized
    fun addCost(editionId: Long, nanos: Long, speechSeconds: Double) {
        val (n, s) = cost(editionId)
        write(editionId, COST, "${n + nanos} ${"%.3f".format(Locale.ROOT, s + speechSeconds)}")
    }

    /** What making [editionId]'s podcast has cost so far, over every run: nanoseconds, and seconds of speech. */
    fun cost(editionId: Long): Pair<Long, Double> =
        read(editionId, COST)?.split(' ')
            ?.let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1)?.toDoubleOrNull() ?: 0.0) } ?: (0L to 0.0)

    /**
     * The line of [page] the app died saying, and how many tries in a row it has: (-1, 0) if none.
     * Only the app dying leaves this behind; see [saying].
     */
    @Synchronized
    fun deaths(editionId: Long, page: Int): Pair<Int, Int> =
        read(editionId, "$page.$SAYING")?.split(' ')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (-1 to 0)

    /**
     * Notes that [line] is being said, and that if the app dies saying it, it's the [death]th time
     * in a row. Kokoro's native code can take the app down, and a line that does would otherwise
     * do it on every charge. [said] clears it.
     */
    @Synchronized
    fun saying(editionId: Long, page: Int, line: Int, death: Int) {
        write(editionId, "$page.$SAYING", "$line $death")
    }

    @Synchronized
    fun said(editionId: Long, page: Int) {
        File(folder(editionId), "$page.$SAYING").delete()
    }

    /** Counts a run that failed on [page] for some other reason than a full disk, and returns how many have. */
    @Synchronized
    fun failed(editionId: Long, page: Int): Int {
        val count = (read(editionId, "$page.$FAILED")?.toIntOrNull() ?: 0) + 1
        write(editionId, "$page.$FAILED", count.toString())
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

    private fun read(editionId: Long, name: String): String? = File(folder(editionId), name).takeIf { it.exists() }?.readText()

    /** Does nothing once [editionId]'s folder is deleted, so nothing finishing late brings it back. */
    private fun write(editionId: Long, name: String, text: String) {
        val folder = folder(editionId)
        if (folder.exists()) File(folder, name).writeText(text)
    }

    @Synchronized
    fun delete(editionId: Long) {
        folder(editionId).deleteRecursively()
        changed()
    }

    @Synchronized
    fun deleteAll() {
        dir.deleteRecursively()
        changed()
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
        const val LINES = "lines"
        const val PART = "part"
    }
}

/** Part of a page's audio, from its [firstLine]: when each of its lines starts, in seconds into [audio]. */
data class PodcastPiece(val audio: File, val firstLine: Int, val starts: List<Double>)
