package com.app.newspaperss.core.edition

import java.time.Instant
import kotlin.random.Random

/** An article that could go into an edition. */
data class Candidate(
    val id: String,
    val sourceId: String,
    val published: Instant?,
    /** Articles the reader asked to bring back go ahead of the rest of their source. */
    val broughtBack: Boolean = false,
)

sealed interface Budget {
    data class Minutes(val minutes: Double) : Budget
    data class Articles(val count: Int) : Budget
}

enum class Ordering {
    /** One article from each source in turn, so no source crowds out the others. */
    TAKE_TURNS,
    /** All of the first source's articles, then the next source's. */
    IN_ORDER,
    SHUFFLE,
}

data class PlanRules(
    val budget: Budget,
    /**
     * Articles per source before any source gets more; null for no cap. It keeps one source from
     * crowding out the rest, so it gives way once every source has had its turn and the budget
     * still has room.
     */
    val maxPerSource: Int? = 1,
    val ordering: Ordering = Ordering.TAKE_TURNS,
    /** Caps for particular sources, by [Candidate.sourceId], in place of [maxPerSource]. */
    val sourceCaps: Map<String, Int> = emptyMap(),
)

object EditionPlanner {
    /**
     * The order in which candidates should be tried for an edition. The cap
     * and budget are applied by [fill], since an article only counts once it
     * has been fetched successfully.
     *
     * Parameters
     * ----------
     * candidates: in any order.
     * sourceOrder: source ids in the reader's order; sources not listed go last.
     * rotation: shifts which source goes first, so the same source doesn't
     *   always get the first slot (pass the edition number).
     */
    fun order(
        candidates: List<Candidate>,
        sourceOrder: List<String>,
        ordering: Ordering,
        rotation: Int = 0,
        random: Random = Random.Default,
    ): List<Candidate> {
        val bySource = candidates.groupBy { it.sourceId }
        val known = sourceOrder.filter { it in bySource }
        val sources = known + (bySource.keys - known.toSet()).sorted()
        val rotated = if (sources.isEmpty()) sources else {
            val shift = Math.floorMod(rotation, sources.size)
            sources.drop(shift) + sources.take(shift)
        }
        val queues = rotated.map { id ->
            bySource.getValue(id).sortedWith(
                compareByDescending<Candidate> { it.broughtBack }.thenByDescending { it.published ?: Instant.MIN },
            )
        }
        return when (ordering) {
            Ordering.IN_ORDER -> queues.flatten()
            Ordering.SHUFFLE -> queues.flatten().shuffled(random)
            Ordering.TAKE_TURNS -> buildList {
                val iterators = queues.map { it.iterator() }
                while (iterators.any { it.hasNext() }) {
                    iterators.forEach { if (it.hasNext()) add(it.next()) }
                }
            }
        }
    }

    /**
     * Fetches [ordered] candidates one at a time until the budget is met,
     * taking at most `maxPerSource` from each source (or its own entry in `sourceCaps`).
     * If that leaves the budget unfilled, the articles `maxPerSource` held back are
     * tried next, in the same order. A source's own cap is a hard limit.
     *
     * The budget is checked before each fetch, so a minutes budget is
     * exceeded by at most one article, and a larger pool doesn't mean more
     * fetching. Candidates [fetch] returns null for (gone, not an article)
     * are skipped without counting, so the source's next article gets its slot.
     */
    suspend fun <T> fill(
        ordered: List<Candidate>,
        rules: PlanRules,
        minutesOf: (T) -> Double,
        fetch: suspend (Candidate) -> T?,
    ): List<T> {
        val picked = mutableListOf<T>()
        val perSource = mutableMapOf<String, Int>()
        val heldBack = mutableListOf<Candidate>()
        var minutes = 0.0
        fun full() = when (val budget = rules.budget) {
            is Budget.Articles -> picked.size >= budget.count
            is Budget.Minutes -> minutes >= budget.minutes
        }
        suspend fun take(candidate: Candidate) {
            val article = fetch(candidate) ?: return
            picked += article
            minutes += minutesOf(article)
        }
        for (candidate in ordered) {
            if (full()) break
            val taken = perSource[candidate.sourceId] ?: 0
            val ownCap = rules.sourceCaps[candidate.sourceId]
            val cap = ownCap ?: rules.maxPerSource
            if (cap != null && taken >= cap) {
                if (ownCap == null) heldBack += candidate
                continue
            }
            val before = picked.size
            take(candidate)
            if (picked.size > before) perSource[candidate.sourceId] = taken + 1
        }
        for (candidate in heldBack) {
            if (full()) break
            take(candidate)
        }
        return picked
    }
}
