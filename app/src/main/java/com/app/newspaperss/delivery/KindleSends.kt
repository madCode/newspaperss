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

/** How an edition went to a Kindle, which decides what its note says. */
enum class KindleSend {
    /** Shared to the Kindle app, which puts it in the library. */
    APP,
    /** Emailed to the Kindle's own address, which delivers it to the device. */
    EMAIL,
}

/**
 * Editions just sent to a Kindle. Amazon takes a few minutes to deliver a book, so for a while
 * the edition says so, and a slow arrival doesn't look like a failure. Kept in memory: it only
 * matters for those minutes.
 */
class KindleSends(private val clock: Clock = Clock.systemUTC()) {
    private data class Sent(val at: Instant, val how: KindleSend)

    private val sent = MutableStateFlow<Map<Long, Sent>>(emptyMap())

    /** [packageName] was picked to send [editionId]; any other app than Kindle clears its note. */
    fun record(editionId: Long, packageName: String) {
        if (packageName == EditionIntents.KINDLE_PACKAGE) record(editionId, KindleSend.APP) else clear(editionId)
    }

    fun record(editionId: Long, how: KindleSend) {
        sent.update { it + (editionId to Sent(clock.instant(), how)) }
    }

    /** [editionId] went another way, or wasn't sent after all: there's nothing to wait for from Amazon. */
    fun clear(editionId: Long) {
        sent.update { it - editionId }
    }

    /** The editions sent to a Kindle in the last [NOTE_FOR], and how, updated as each one's time runs out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: Flow<Map<Long, KindleSend>> = sent.transformLatest { all ->
        while (true) {
            val now = clock.instant()
            val live = all.filterValues { Duration.between(it.at, now) < NOTE_FOR }
            emit(live.mapValues { it.value.how })
            val firstToGo = live.values.minOfOrNull { it.at } ?: break
            delay(Duration.between(now, firstToGo.plus(NOTE_FOR)).toMillis().coerceAtLeast(1))
        }
    }

    companion object {
        val NOTE_FOR: Duration = Duration.ofMinutes(30)
    }
}
