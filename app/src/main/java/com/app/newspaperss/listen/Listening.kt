package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import kotlinx.coroutines.flow.first

/** An edition left part way through: offered before starting a newer one. */
data class Unfinished(val editionId: Long, val title: String, val minutesLeft: Double)

/** What the edition page needs to offer listening: where each edition was left, and starting one. */
class Listening(
    val player: ListenPlayer,
    private val progress: ListenProgress,
    private val editions: EditionRepository,
    /** Podcasts made ahead, shown under Listen while Kokoro is in use; null without them. */
    val podcasts: Podcasts? = null,
    /** Binds the service that keeps it playing with the screen off. */
    private val connect: () -> Unit,
) {
    /** Where [editionId] was left, unless it was heard to the end or never started. */
    fun saved(editionId: Long): ListenPosition? = progress.get(editionId)?.takeUnless { progress.finished(editionId) }

    /**
     * The most recently heard edition older than [editionId] that was left part way, if its book
     * is still here: yesterday's paper, to finish before today's.
     */
    suspend fun earlierUnfinished(editionId: Long): Unfinished? {
        for (id in progress.unfinished()) {
            if (id >= editionId) continue
            val edition = editions.byId(id) ?: continue
            if (edition.status == EditionStatus.DELETED || editions.fileOf(edition) == null) continue
            val at = progress.get(id) ?: continue
            val contents = editions.observeContents(id).first().sortedBy { it.entry.position }
            val left = contents.drop(at.page).sumOf { ListenTime.fromReading(it.entry.minutes) }
            return Unfinished(id, edition.title, left)
        }
        return null
    }

    fun start(editionId: Long) {
        connect()
        player.start(editionId)
    }
}
