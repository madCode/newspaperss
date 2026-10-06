package com.app.newspaperss.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.app.newspaperss.core.ttrss.TtrssCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val Context.feedMovesStore by preferencesDataStore(
    name = "feed_moves",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Moving phone feeds into the reader's tt-rss, in the server setup. A batch runs as background
 * work ([run]), since tt-rss fetches each feed before it answers, and everything it still has to
 * do is kept here rather than in memory, so a batch cut off by the app dying carries on where it
 * stopped.
 *
 * Each feed is subscribed to first (or found already there); once the batch has been asked, the
 * account's feeds are listed once, and each moved feed's settings are carried onto its tt-rss
 * feed and the phone feed is retired: paused, so it fetches nothing, and hidden from Sources.
 * Deleting it would delete its articles, stars included, so it stays until it has nothing
 * starred, waiting or in an unsent edition ([tidy]); meanwhile editions still take what it has.
 *
 * @param schedule starts the work that calls [run]; must return quickly.
 */
class FeedMoves(
    private val store: DataStore<Preferences>,
    private val db: AppDatabase,
    private val ttrss: TtrssRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val schedule: () -> Unit,
) {
    constructor(context: Context, db: AppDatabase, ttrss: TtrssRepository, schedule: () -> Unit) :
        this(context.feedMovesStore, db, ttrss, schedule = schedule)

    /** A feed tt-rss has: [feedId] as it said, if it did; [wasPaused] on the phone before the move. */
    data class Subscribed(val sourceId: Long, val feedId: Int?, val already: Boolean, val wasPaused: Boolean)

    data class Failure(val sourceId: Long, val reason: String)

    /**
     * @property queued phone feeds still to ask tt-rss about.
     * @property subscribed feeds tt-rss has, waiting for their settings to be carried over.
     * @property total how many the batch set out to move; [moved] how many it has.
     * @property retiring moved phone feeds kept, paused and hidden, until nothing is left in them,
     *   with whether each was paused before it moved.
     */
    data class State(
        val queued: Set<Long> = emptySet(),
        val subscribed: List<Subscribed> = emptyList(),
        val failed: List<Failure> = emptyList(),
        val total: Int = 0,
        val moved: Int = 0,
        val retiring: Map<Long, Boolean> = emptyMap(),
        internal val batch: String? = null,
    ) {
        val running get() = queued.isNotEmpty() || subscribed.isNotEmpty()

        /** A batch has ended and its result is still to be shown. */
        val finished get() = !running && total > 0

        /** A moved source still kept: Sources doesn't show it. Resumed from its page, it's a phone feed again. */
        fun hides(source: SourceEntity) = source.paused && source.id in retiring
    }

    private object Keys {
        val batch = stringPreferencesKey("batch")
        val queued = stringSetPreferencesKey("queued")
        val subscribed = stringSetPreferencesKey("subscribed")
        val failed = stringSetPreferencesKey("failed")
        val total = intPreferencesKey("total")
        val moved = intPreferencesKey("moved")
        val category = intPreferencesKey("category_id")
        val loginUrl = stringPreferencesKey("login_api_url")
        val loginUser = stringPreferencesKey("login_user")
        val before = stringSetPreferencesKey("listed_before")
        val retiring = stringSetPreferencesKey("retiring")
    }

    val state: Flow<State> = store.data.map(::read)

    suspend fun current(): State = state.first()

    /**
     * Starts moving [sourceIds] into [category], under the login signed in now. Returns false,
     * starting nothing, while another batch is still running, or with no usable login.
     */
    suspend fun start(sourceIds: Collection<Long>, category: TtrssCategory): Boolean {
        if (sourceIds.isEmpty()) return false
        val login = ttrss.login() ?: return false
        // Listed before anything is subscribed: on a server that doesn't say a new feed's id,
        // the moved feed is the one new since, never a near-same address the reader already had.
        val before = ttrss.listedKeys()
        var started = false
        store.edit { p ->
            if (read(p).running) return@edit
            p[Keys.batch] = UUID.randomUUID().toString()
            p[Keys.queued] = sourceIds.map { it.toString() }.toSet()
            p[Keys.subscribed] = emptySet()
            p[Keys.failed] = emptySet()
            p[Keys.total] = sourceIds.toSet().size
            p[Keys.moved] = 0
            p[Keys.category] = category.id
            p[Keys.loginUrl] = login.apiUrl
            p[Keys.loginUser] = login.user
            p[Keys.before] = before
            started = true
        }
        if (started) schedule()
        return started
    }

    /** Schedules the work again if a batch is waiting, e.g. one stored just before the app died. */
    suspend fun resumeIfPending() {
        if (current().running) schedule()
    }

    /** Asks tt-rss about each queued feed in Sources' order, then carries settings over and retires them. */
    suspend fun run() {
        val p = store.data.first()
        val batch = p[Keys.batch] ?: return
        val login = TtrssRepository.Login(p[Keys.loginUrl] ?: return, p[Keys.loginUser] ?: "")
        val category = p[Keys.category] ?: 0
        val before = p[Keys.before].orEmpty()
        while (true) {
            val queued = current().takeIf { it.batch == batch }?.queued ?: return
            if (queued.isEmpty()) break
            val source = db.sources().all().firstOrNull { it.id in queued && it.kind == SourceKind.FEED }
            if (source == null) {
                // Every one left was removed while waiting its turn: none is moved, nor counted.
                edit(batch) { s -> s.copy(queued = s.queued - queued, total = s.total - queued.size) }
                continue
            }
            val answer = when (ttrss.login()) {
                null -> TtrssRepository.MoveAnswer.Failed(FeedSync.SIGN_IN_AGAIN, stopsBatch = true)
                login -> ttrss.feedAt(source.url)?.key?.toIntOrNull()?.let { TtrssRepository.MoveAnswer.In(it, already = true) }
                    ?: ttrss.subscribeForMove(source.url, category, login)
                else -> TtrssRepository.MoveAnswer.Failed(TtrssRepository.SIGNED_IN_AGAIN, stopsBatch = true)
            }
            edit(batch) { s ->
                when (answer) {
                    is TtrssRepository.MoveAnswer.In -> s.copy(
                        queued = s.queued - source.id,
                        subscribed = s.subscribed + Subscribed(source.id, answer.feedId, answer.already, source.paused),
                    )
                    is TtrssRepository.MoveAnswer.Failed -> {
                        val givenUp = if (answer.stopsBatch) s.queued else setOf(source.id)
                        s.copy(queued = s.queued - givenUp, failed = s.failed + givenUp.map { Failure(it, answer.reason) })
                    }
                }
            }
        }
        finish(batch, login, before)
        tidy()
    }

    /** The second half of [run]: lists the account's feeds once, then carries each feed's settings over. */
    private suspend fun finish(batch: String, login: TtrssRepository.Login, before: Set<String>) {
        val subscribed = current().takeIf { it.batch == batch }?.subscribed.orEmpty()
        if (subscribed.isEmpty()) return
        if (ttrss.login() != login) {
            edit(batch) { s -> s.copy(subscribed = emptyList(), failed = s.failed + s.subscribed.map { Failure(it.sourceId, TtrssRepository.SIGNED_IN_AGAIN) }) }
            return
        }
        // A list that fails leaves the feeds tt-rss gave an id for to be carried anyway.
        val listed = ttrss.listFor(login)
        for (moved in subscribed) {
            // Stopped meanwhile (leaving the server): pausing the feed now would leave it paused.
            if (current().batch != batch) return
            val source = db.sources().byId(moved.sourceId)
            val target = source?.let { ttrss.movedFeedAt(it.url, moved.feedId, moved.already, before) }
            when {
                source == null -> edit(batch) { s -> s.copy(subscribed = s.subscribed - moved, total = s.total - 1) }
                target == null -> edit(batch) { s ->
                    s.copy(subscribed = s.subscribed - moved, failed = s.failed + Failure(moved.sourceId, if (listed) NOT_FOUND else NOT_LISTED))
                }
                else -> {
                    carry(source, target, moved.wasPaused)
                    edit(batch) { s ->
                        s.copy(subscribed = s.subscribed - moved, moved = s.moved + 1, retiring = s.retiring + (moved.sourceId to moved.wasPaused))
                    }
                }
            }
        }
    }

    /**
     * Copies the phone feed's settings onto its tt-rss feed, and pauses the phone feed, in one
     * transaction. The phone's settings win over the tt-rss feed's own, for a feed already there
     * too: it's the one that was in the paper. A feed paused on the phone is left out in tt-rss,
     * and its waiting articles go, which the paper wasn't taking either.
     */
    private suspend fun carry(source: SourceEntity, target: PublicationEntity, wasPaused: Boolean) = db.withTransaction {
        val sources = db.sources()
        val phone = sources.publication(source.id, PublicationEntity.OWN) ?: PublicationEntity(source.id, PublicationEntity.OWN)
        // Read again inside the transaction: the list may have filled it in since.
        val current = sources.publication(target.sourceId, target.key) ?: target
        sources.savePublication(
            current.copy(
                contentMode = phone.contentMode,
                fullTextEvidence = phone.fullTextEvidence,
                fullTextStreak = phone.fullTextStreak,
                fullTextDay = phone.fullTextDay,
                checkedDay = phone.checkedDay,
                chosenMode = phone.chosenMode,
                maxArticles = phone.maxArticles,
                skipPaidPosts = phone.skipPaidPosts,
                leftOut = phone.leftOut || wasPaused,
            ),
        )
        sources.setPaused(source.id, true)
        if (wasPaused) db.articles().expireWaiting(source.id)
        db.articles().rememberRead(source.id, clock.instant())
    }

    /**
     * Deletes the moved phone feeds with nothing left in them, and forgets any resumed from their
     * page since, which are phone feeds again.
     */
    suspend fun tidy() {
        val retiring = current().retiring.keys
        if (retiring.isEmpty()) return
        val paused = db.sources().all().filter { it.id in retiring && it.paused }.map { it.id }
        if (paused.isNotEmpty()) db.sources().deleteSpent(paused, clock.instant().minus(KEEP_AFTER_DELIVERY))
        val kept = db.sources().all().filter { it.id in paused }.map { it.id }.toSet()
        val gone = retiring - kept
        if (gone.isNotEmpty()) store.edit { p -> write(p, read(p).let { it.copy(retiring = it.retiring - gone) }) }
    }

    /**
     * Leaving the server: moved feeds still kept are phone feeds again (unless they were paused
     * before), and a batch under way stops, its feeds left on the phone. Without this they'd stay
     * hidden with no server to come from.
     */
    suspend fun restore() {
        current().retiring.forEach { (id, wasPaused) -> if (!wasPaused) db.sources().setPaused(id, false) }
        store.edit { it.clear() }
    }

    /**
     * Ends a batch that can't go on (the work kept failing): what's left stays on the phone, said
     * with [reason], so the banner offers it again rather than saying it's moving for good.
     */
    suspend fun giveUp(reason: String) = store.edit { p ->
        val s = read(p)
        if (s.running) {
            write(p, s.copy(queued = emptySet(), subscribed = emptyList(), failed = s.failed + (s.queued + s.subscribed.map { it.sourceId }).map { Failure(it, reason) }))
        }
    }

    /** A batch that moved everything has been reported; a partial one stays until the next. */
    suspend fun resultShown() = store.edit { p ->
        val s = read(p)
        if (s.finished && s.failed.isEmpty()) write(p, s.copy(total = 0, moved = 0))
    }

    /** Moved phone feeds that editions still take from, though they're paused. */
    suspend fun retiringIds(): Set<Long> = current().retiring.keys

    /** Changes the state, unless the batch has been replaced or stopped ([restore]) meanwhile. */
    private suspend fun edit(batch: String, change: suspend (State) -> State) = store.edit { p ->
        val s = read(p)
        if (s.batch == batch) write(p, change(s))
    }

    private fun write(p: MutablePreferences, s: State) {
        p[Keys.queued] = s.queued.map { it.toString() }.toSet()
        p[Keys.subscribed] = s.subscribed.map { listOf(it.sourceId, it.feedId ?: "", it.already.flag, it.wasPaused.flag).joinToString(SEP) }.toSet()
        p[Keys.failed] = s.failed.map { "${it.sourceId}$SEP${it.reason}" }.toSet()
        p[Keys.total] = s.total
        p[Keys.moved] = s.moved
        p[Keys.retiring] = s.retiring.map { (id, paused) -> "$id$SEP${paused.flag}" }.toSet()
    }

    private fun read(p: Preferences): State = State(
        queued = p[Keys.queued].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
        subscribed = p[Keys.subscribed].orEmpty().mapNotNull { line ->
            val f = line.split(SEP)
            if (f.size != 4) null else f[0].toLongOrNull()?.let { Subscribed(it, f[1].toIntOrNull(), f[2] == "1", f[3] == "1") }
        }.sortedBy { it.sourceId },
        failed = p[Keys.failed].orEmpty().mapNotNull { line ->
            val f = line.split(SEP, limit = 2)
            f.getOrNull(1)?.let { reason -> f[0].toLongOrNull()?.let { Failure(it, reason) } }
        }.sortedBy { it.sourceId },
        total = p[Keys.total] ?: 0,
        moved = p[Keys.moved] ?: 0,
        retiring = p[Keys.retiring].orEmpty().mapNotNull { line ->
            val f = line.split(SEP)
            f[0].toLongOrNull()?.let { it to (f.getOrNull(1) == "1") }
        }.toMap(),
        batch = p[Keys.batch],
    )

    private val Boolean.flag get() = if (this) "1" else "0"

    companion object {
        private const val SEP = "|"

        /** tt-rss subscribed to it but its feeds couldn't be listed: moving it again finds it. */
        const val NOT_LISTED = "tt-rss has it now, but hasn't listed it yet. Move it again in a while: it won't be added twice."

        /** Listed, but no feed there is surely this one: tt-rss may have it under another address. */
        const val NOT_FOUND = "tt-rss has it, but under another address. Check it's there in tt-rss, then remove it here."

        /** How long a moved feed is kept after an edition with its articles is delivered, for Mark as not sent. */
        private val KEEP_AFTER_DELIVERY: Duration = Duration.ofDays(14)
    }
}
