package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.abs

/** The speeds Listen reads at, in the order its speed button steps through them. */
val LISTEN_SPEEDS = listOf(1f, 1.2f, 1.5f, 0.8f)

data class ListenState(
    val editionId: Long? = null,
    val editionTitle: String = "",
    val pages: List<ListenPage> = emptyList(),
    val at: ListenPosition = ListenPosition(),
    /** The page at [at], once it's read out of the book. */
    val script: ListenScript? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Heard to its closing page. */
    val finished: Boolean = false,
    val speed: Float = 1f,
    /** The page's language, when the phone has no voice for it and reads it in its own. */
    val missingLanguage: String? = null,
    val error: String? = null,
) {
    /** About how far in, and how long the whole edition takes, in seconds at normal speed. */
    val secondsIn: Double get() = pages.take(at.page).sumOf { it.minutes * 60 } + secondsInPage

    /**
     * How far into the page being read: the share of its sentences heard, of the page's time.
     * The page's time comes from its reading time, as on the edition page, so the two agree.
     */
    val secondsInPage: Double
        get() {
            val script = script ?: return 0.0
            if (script.seconds <= 0.0) return 0.0
            return script.secondsBefore(at.line) / script.seconds * (pages.getOrNull(at.page)?.minutes ?: 0.0) * 60
        }
    val secondsTotal: Double get() = pages.sumOf { it.minutes * 60 }
}

/**
 * Reads an edition aloud, a line at a time, from where it was left: the one player behind the
 * edition's Listen button, the playing screen and the lock screen's controls. Call it from the
 * main thread.
 *
 * The voice is given the line being read and the one after, so there's no gap between them, and
 * each line's start moves the position: that's what the screen tints and what's saved. Lines
 * carry a generation number, so the end of a line from before a pause or a jump can't move it.
 *
 * The speed is kept in Settings: the player follows [savedSpeed], and [setSpeed] saves to it.
 */
class ListenPlayer(
    private val speaker: Speaker,
    private val progress: ListenProgress,
    private val open: suspend (editionId: Long) -> ListenBook?,
    private val scope: CoroutineScope,
    private val savedSpeed: Flow<Float> = emptyFlow(),
    private val saveSpeed: suspend (Float) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Played between articles when one runs into the next, not when the listener skips. */
    private val chime: Chime = Chime.NONE,
) : Speaker.Listener {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private var book: ListenBook? = null
    private var job: Job? = null
    private var generation = 0
    private var queued = -1
    private var lineStartedAt = 0L
    private var errors = 0
    /** A page is being read out of the book: until it is, the script and position are the old page's. */
    private var turning = false
    private var turns = 0
    /** The pause between articles is playing: the position is already the next article's start. */
    private var chiming = false
    private var savingSpeed = 0

    init {
        speaker.listener = this
        scope.launch {
            // Until a speed set here is saved, the store still hands back the one before it.
            savedSpeed.collect { if (savingSpeed == 0) applySpeed(it) }
        }
    }

    /** Starts [editionId] at [from], or where it was left (from the top once it was finished). */
    fun start(editionId: Long, from: ListenPosition? = null) {
        if (_state.value.editionId == editionId && book != null) {
            if (from != null) seek(from) else play()
            return
        }
        silence()
        job?.cancel()
        // A turn cancelled before it ran never reaches its finally.
        turning = false
        book?.close()
        book = null
        _state.value = ListenState(editionId = editionId, loading = true, playing = true, speed = _state.value.speed)
        job = scope.launch {
            val opened = open(editionId)
            if (opened == null) {
                _state.update { it.copy(loading = false, playing = false, error = "This edition's file is gone, so it can't be read aloud.") }
                return@launch
            }
            book = opened
            _state.update { it.copy(editionTitle = opened.title, pages = opened.pages, loading = false) }
            // The store's first value can come after the book opens, and the first line would restart at it.
            if (savingSpeed == 0) savedSpeed.firstOrNull()?.let { speed -> _state.update { it.copy(speed = speed) } }
            val at = from ?: progress.get(editionId)?.takeUnless { progress.finished(editionId) } ?: ListenPosition()
            goTo(at)
        }
    }

    fun play() {
        val current = _state.value
        if (book == null || current.loading) return
        if (current.finished) {
            move(ListenPosition(), play = true)
            return
        }
        if (current.script == null) {
            move(current.at, play = true)
            return
        }
        _state.update { it.copy(playing = true, error = null) }
        // Mid-turn, the new page starts itself once it's read; mid-pause, once the pause ends.
        if (!turning && !chiming) speakFrom(current.at.line)
    }

    fun pause() {
        silence()
        _state.update { it.copy(playing = false) }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    /**
     * ↶: while playing, back to the start of the sentence being read, or to the one before if
     * it has only just started (so a second tap goes further back). Paused, the one before.
     */
    fun back() {
        val current = _state.value
        if (turning) return
        // In the pause, the last thing heard is the article before's end.
        if (chiming) return move(ListenPosition(current.at.page - 1, Int.MAX_VALUE), play = true)
        val script = current.script ?: return
        val restart = current.playing && now() - lineStartedAt >= BACK_GRACE_MS
        val line = if (restart) current.at.line else current.at.line - 1
        when {
            line >= 0 -> goToLine(line, script)
            current.at.page > 0 -> move(ListenPosition(current.at.page - 1, Int.MAX_VALUE), current.playing)
            else -> goToLine(0, script)
        }
    }

    /** ↷: the next sentence. */
    fun forward() {
        val current = _state.value
        if (turning) return
        // In the pause, on to the next article's first sentence, not past it.
        if (chiming) return speakFrom(current.at.line)
        val script = current.script ?: return
        if (current.at.line < script.lines.lastIndex) goToLine(current.at.line + 1, script) else nextPage(current.playing)
    }

    /** ⏭: the next article. */
    fun next() = nextPage(_state.value.playing)

    /** ⏮: back to the start of this article, or the one before from its first few sentences. */
    fun previous() {
        val current = _state.value
        val page = if (current.at.line >= RESTART_AFTER_LINES || current.at.page == 0) current.at.page else current.at.page - 1
        move(ListenPosition(page, 0), current.playing)
    }

    /** Plays from [position]: a tapped sentence, or an article picked from the contents. */
    fun seek(position: ListenPosition) = move(position, play = true)

    /** Sets and saves the speed: one of [LISTEN_SPEEDS], the nearest to what a car or watch asks for. */
    fun setSpeed(speed: Float) {
        val listed = LISTEN_SPEEDS.minBy { abs(it - speed) }
        applySpeed(listed)
        savingSpeed++
        scope.launch {
            try {
                saveSpeed(listed)
            } catch (_: IOException) {
                // Not kept (a full disk, say): the catch-up below goes back to the kept speed rather than crash.
            } finally {
                savingSpeed--
            }
            // A change made elsewhere while this was saving was passed over: catch up with the store.
            if (savingSpeed == 0) savedSpeed.firstOrNull()?.let(::applySpeed)
        }
    }

    /** Says [text] at [speed] in the phone's own voice, pausing the edition: Settings' sample. */
    fun sample(text: String, speed: Float) {
        if (_state.value.playing) pause() else silence()
        // Its id names no line, so its start and end move nothing.
        speaker.speak(SAMPLE_ID, text, null, speed, flush = true)
    }

    /** A picture on the page being read, for the screen. */
    suspend fun image(src: String): ByteArray? = book?.image(src)

    /** Lets [editionId] go if it's the one loaded: its book was deleted. */
    fun forget(editionId: Long) {
        if (_state.value.editionId == editionId) stop()
    }

    /** Stops and lets the edition go, closing its book. */
    fun stop() {
        silence()
        job?.cancel()
        turning = false
        book?.close()
        book = null
        _state.value = ListenState(speed = _state.value.speed)
    }

    fun release() {
        stop()
        chime.release()
        speaker.release()
    }

    private fun applySpeed(speed: Float) {
        if (speed == _state.value.speed) return
        _state.update { it.copy(speed = speed) }
        // Mid-pause, the next article starts at the new speed when the pause ends.
        if (_state.value.playing && !turning && !chiming) speakFrom(_state.value.at.line)
    }

    private fun nextPage(play: Boolean, chimeFirst: Boolean = false) {
        val current = _state.value
        if (current.at.page < current.pages.lastIndex) move(ListenPosition(current.at.page + 1, 0), play, chimeFirst) else finish()
    }

    private fun goToLine(line: Int, script: ListenScript) {
        val current = _state.value
        val at = ListenPosition(current.at.page, line.coerceIn(0, script.lines.lastIndex))
        _state.update { it.copy(at = at) }
        save(at)
        if (current.playing) speakFrom(at.line)
    }

    private fun move(position: ListenPosition, play: Boolean, chimeFirst: Boolean = false) {
        silence()
        job?.cancel()
        // Playing or not is decided now, so a pause while the page is read out of the book holds.
        _state.update { it.copy(playing = play) }
        turning = true
        val turn = ++turns
        job = scope.launch {
            try {
                goTo(position, chimeFirst)
            } finally {
                // A cancelled turn ends after the one that replaced it began.
                if (turn == turns) turning = false
            }
        }
    }

    private suspend fun goTo(position: ListenPosition, chimeFirst: Boolean = false) {
        val opened = book ?: return
        val page = position.page.coerceIn(0, opened.pages.lastIndex)
        val current = _state.value
        val script = if (page == current.at.page && current.script != null) current.script else opened.script(page)
        if (script == null) {
            // The book can't be read (deleted or pruned before it was opened, or broken): say so,
            // and keep the place, rather than pass over every page to "the end".
            silence()
            _state.update { it.copy(playing = false, error = "This edition's file can't be read, so it can't be read aloud.") }
            return
        }
        if (script.lines.isEmpty()) {
            // A page with nothing to say, like an article that's only a video, is passed over.
            if (page < opened.pages.lastIndex) goTo(ListenPosition(page + 1, 0), chimeFirst) else finish()
            return
        }
        val at = ListenPosition(page, position.line.coerceIn(0, script.lines.lastIndex))
        _state.update { it.copy(at = at, script = script, finished = false, missingLanguage = null, error = null) }
        save(at)
        if (!_state.value.playing) return
        if (!chimeFirst) {
            speakFrom(at.line)
            return
        }
        // A pause, skip or stop during the chime moves the generation on, and the chime with it.
        val chimed = generation
        chiming = true
        chime.play {
            if (generation != chimed || !_state.value.playing) return@play
            chiming = false
            speakFrom(at.line)
        }
    }

    private fun finish() {
        silence()
        val current = _state.value
        current.editionId?.let(progress::finish)
        _state.update { it.copy(playing = false, finished = true) }
    }

    private fun silence() {
        generation++
        queued = -1
        chiming = false
        chime.stop()
        speaker.stop()
    }

    private fun speakFrom(line: Int) {
        silence()
        queued = line
        say(line, flush = true)
        queueNext()
    }

    private fun queueNext() {
        val script = _state.value.script ?: return
        if (queued < script.lines.lastIndex) {
            queued++
            say(queued, flush = false)
        }
    }

    private fun say(line: Int, flush: Boolean) {
        val current = _state.value
        val script = current.script ?: return
        val id = LineId(generation, current.editionId ?: return, current.at.page, line, script.lines.size)
        val installed = speaker.speak(id.toString(), script.lines[line].spoken, script.language, current.speed, flush)
        val missing = script.language.takeUnless { installed }
        if (missing != current.missingLanguage) _state.update { it.copy(missingLanguage = missing) }
    }

    private fun save(at: ListenPosition) {
        _state.value.editionId?.let { progress.set(it, at) }
    }

    /** The line an id names, if it's of the current generation and page. */
    private fun lineOf(id: String): Int? {
        val line = LineId.parse(id) ?: return null
        val current = _state.value
        if (line.generation != generation || line.editionId != current.editionId || line.page != current.at.page) return null
        return line.line
    }

    override fun onStart(id: String) {
        val line = lineOf(id) ?: return
        lineStartedAt = now()
        errors = 0
        val current = _state.value
        if (line != current.at.line) {
            val at = current.at.copy(line = line)
            _state.update { it.copy(at = at) }
            save(at)
        }
        if (queued == line) queueNext()
    }

    override fun onDone(id: String) {
        val line = lineOf(id) ?: return
        val script = _state.value.script ?: return
        if (line == script.lines.lastIndex) nextPage(play = true, chimeFirst = true)
    }

    /** One line the voice can't say is passed over; three in a row, and it's the voice that's stuck. */
    override fun onError(id: String) {
        val line = lineOf(id) ?: return
        if (++errors >= MAX_ERRORS) {
            silence()
            _state.update { it.copy(playing = false, error = "The phone's voice isn't working. Check Android's text-to-speech settings, then try again.") }
            return
        }
        onDone(id)
        if (queued == line) queueNext()
    }

    private companion object {
        const val BACK_GRACE_MS = 2_000L
        const val RESTART_AFTER_LINES = 3
        const val MAX_ERRORS = 3
        const val SAMPLE_ID = "sample"
    }
}
