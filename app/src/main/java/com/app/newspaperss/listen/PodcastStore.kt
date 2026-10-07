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
 * Kokoro) nothing writes into it again: a piece finishing after that is dropped.
 */
class PodcastStore(val dir: File) {
    private val _changes = MutableStateFlow(0)

    /** Moves on whenever a podcast is asked for, gains a page, is finished or deleted: for screens to follow. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    private fun folder(editionId: Long) = File(dir, editionId.toString())

    private fun voiceFile(editionId: Long) = File(folder(editionId), VOICE)

    /** Asks for [editionId]'s podcast in [voice]. Asked again, it keeps the voice it was started in. */
    @Synchronized
    fun want(editionId: Long, voice: PodcastVoice) {
        folder(editionId).mkdirs()
        // Rewritten if unreadable: the app may have died as it was first written.
        if (voice(editionId) == null) voiceFile(editionId).writeText(voice.name)
        changed()
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
        changed()
    }

    @Synchronized
    fun complete(editionId: Long, page: Int) {
        mark(editionId, "$page.$MADE")
        changed()
    }

    /** Leaves [page] to the phone's voice, dropping any of it made: an article doesn't change voice part way. */
    @Synchronized
    fun leaveLive(editionId: Long, page: Int) {
        folder(editionId).listFiles { file -> file.name.startsWith("$page$SEP") }?.forEach { it.delete() }
        mark(editionId, "$page.$LIVE")
        changed()
    }

    @Synchronized
    fun finish(editionId: Long) {
        mark(editionId, FINISHED)
        changed()
    }

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
        const val PART = "part"
    }
}

/** Part of a page's audio, from its [firstLine]: when each of its lines starts, in seconds into [audio]. */
data class PodcastPiece(val audio: File, val firstLine: Int, val starts: List<Double>)
