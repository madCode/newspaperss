package com.app.newspaperss.core.edition

import java.time.Instant
import kotlin.random.Random

/** An article that could go into an edition. */
data class Candidate(
    val id: String,
    val sourceId: String,
    val published: Instant?,
    /**
     * When the reader starred it for the next edition, or null. Starred articles go ahead of
     * every unstarred one, oldest star first within a source.
     */
    val starredAt: Instant? = null,
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
     * Starred candidates come first, arranged among themselves by [ordering] (taking turns
     * across sources, oldest star first within one), then the rest the same way. So a star
     * takes its source's slot ahead of unstarred articles, and [fill] holds back extra stars
     * ahead of extra unstarred ones.
     *
     * @param candidates in any order.
     * @param sourceOrder source ids in the reader's order; sources not listed go last.
     * @param lastFeatured when each source last had an article in an edition. Sources go in turn from
     *   the one featured longest ago (never first), so with more sources than fit, the next
     *   edition picks up the ones the last one left out.
     * @param rotation breaks ties between sources featured at the same time (pass the edition count).
     *   All of one edition's sources tie, and without it the first of them in the reader's order
     *   would go in every time whenever most sources fit.
     */
    fun order(
        candidates: List<Candidate>,
        sourceOrder: List<String>,
        ordering: Ordering,
        lastFeatured: Map<String, Instant> = emptyMap(),
        rotation: Int = 0,
        random: Random = Random.Default,
    ): List<Candidate> {
        val (starred, rest) = candidates.partition { it.starredAt != null }
        val sources = sourcesInTurn(candidates, sourceOrder, lastFeatured, rotation)
        val oldestStarFirst = compareBy<Candidate> { it.starredAt }
        val newestFirst = compareByDescending<Candidate> { it.published ?: Instant.MIN }
        return arrange(starred, sources, oldestStarFirst, ordering, random) + arrange(rest, sources, newestFirst, ordering, random)
    }

    private fun sourcesInTurn(candidates: List<Candidate>, sourceOrder: List<String>, lastFeatured: Map<String, Instant>, rotation: Int): List<String> {
        val present = candidates.map { it.sourceId }.toSet()
        val known = sourceOrder.filter { it in present }
        val sources = known + (present - known.toSet()).sorted()
        if (sources.isEmpty()) return sources
        val shift = Math.floorMod(rotation, sources.size)
        // A stable sort, so sources featured at the same time keep the rotated order.
        return (sources.drop(shift) + sources.take(shift)).sortedBy { lastFeatured[it] ?: Instant.MIN }
    }

    private fun arrange(
        candidates: List<Candidate>,
        sources: List<String>,
        withinSource: Comparator<Candidate>,
        ordering: Ordering,
        random: Random,
    ): List<Candidate> {
        val bySource = candidates.groupBy { it.sourceId }
        val queues = sources.mapNotNull { bySource[it]?.sortedWith(withinSource) }
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
     * tried next, in the same order (so held-back stars before held-back unstarred ones,
     * given [order]'s stars-first list). A source's own cap is a hard limit, stars included.
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
