package com.app.newspaperss.delivery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Editions just sent with the Kindle app. Send to Kindle takes a few minutes to put a book in the
 * library, so for a while the edition says so, and a slow arrival doesn't look like a failure.
 * Kept in memory: it only matters for those minutes.
 */
class KindleSends(private val clock: Clock = Clock.systemUTC()) {
    private val sent = MutableStateFlow<Map<Long, Instant>>(emptyMap())

    /** [packageName] was picked to send [editionId]; any other app than Kindle clears its note. */
    fun record(editionId: Long, packageName: String) {
        if (packageName == EditionIntents.KINDLE_PACKAGE) sent.update { it + (editionId to clock.instant()) } else clear(editionId)
    }

    /** [editionId] went another way, or wasn't sent after all: there's nothing to wait for in the Kindle library. */
    fun clear(editionId: Long) {
        sent.update { it - editionId }
    }

    /** The editions sent with the Kindle app in the last [NOTE_FOR], updated as each one's time runs out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: Flow<Set<Long>> = sent.transformLatest { all ->
        while (true) {
            val now = clock.instant()
            val live = all.filterValues { Duration.between(it, now) < NOTE_FOR }
            emit(live.keys)
            val firstToGo = live.values.minOrNull() ?: break
            delay(Duration.between(now, firstToGo.plus(NOTE_FOR)).toMillis().coerceAtLeast(1))
        }
    }

    companion object {
        val NOTE_FOR: Duration = Duration.ofMinutes(30)
    }
}
