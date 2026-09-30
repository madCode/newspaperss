package com.app.newspaperss.core.edition

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class EditionPlannerTest {
    private fun c(id: String, source: String, day: Int, starredDay: Int? = null) =
        Candidate(id, source, Instant.parse("2026-09-%02dT00:00:00Z".format(day)), starredDay?.let { Instant.parse("2026-09-%02dT12:00:00Z".format(it)) })

    private fun day(d: Int) = Instant.parse("2026-09-%02dT06:00:00Z".format(d))

    private val pool = listOf(
        c("a1", "a", 1), c("a2", "a", 2), c("a3", "a", 3),
        c("b1", "b", 1),
        c("c1", "c", 1), c("c2", "c", 2),
    )

    private fun ids(list: List<Candidate>) = list.map { it.id }

    private val abc = listOf("a", "b", "c")

    @Test
    fun takesTurnsNewestFirstWithinEachSource() {
        assertEquals(listOf("a3", "b1", "c2", "a2", "c1", "a1"), ids(EditionPlanner.order(pool, abc, Ordering.TAKE_TURNS)))
    }

    @Test
    fun starsGoFirstTakingTurnsAcrossSourcesOldestStarFirst() {
        // An old article starred late still comes after one starred earlier: the reader's own
        // order of starring, not the feed's.
        val withStars = pool + c("a0", "a", 1, starredDay = 20) + c("a9", "a", 9, starredDay = 10) + c("c0", "c", 1, starredDay = 15)
        assertEquals(
            listOf("a9", "c0", "a0", "a3", "b1", "c2", "a2", "c1", "a1"),
            ids(EditionPlanner.order(withStars, abc, Ordering.TAKE_TURNS)),
        )
    }

    @Test
    fun inOrderAndShufflePutStarsFirstToo() {
        val withStars = pool + c("c0", "c", 1, starredDay = 15)
        assertEquals(listOf("c0", "a3", "a2", "a1", "b1", "c2", "c1"), ids(EditionPlanner.order(withStars, abc, Ordering.IN_ORDER)))
        assertEquals("c0", EditionPlanner.order(withStars, abc, Ordering.SHUFFLE, random = Random(3)).first().id)
    }

    @Test
    fun aStarTakesItsSourcesSlotAndExtraStarsAreHeldBackAheadOfUnstarred() = runTest {
        val withStars = pool + c("a0", "a", 1, starredDay = 10) + c("a8", "a", 8, starredDay = 11)
        val ordered = EditionPlanner.order(withStars, abc, Ordering.TAKE_TURNS)
        assertEquals(
            "a's slot goes to its first star, then its second star beats its unstarred articles once the cap gives way",
            listOf("a0", "b1", "c2", "a8", "a3"),
            EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(5), maxPerSource = 1), { 1.0 }) { it.id },
        )
    }

    @Test
    fun aStarBeyondASourcesOwnCapWaits() = runTest {
        val withStars = pool + c("a0", "a", 1, starredDay = 10) + c("a8", "a", 8, starredDay = 11)
        val ordered = EditionPlanner.order(withStars, abc, Ordering.TAKE_TURNS)
        val result = EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(10), maxPerSource = 1, sourceCaps = mapOf("a" to 1)), { 1.0 }) { it.id }
        assertEquals(listOf("a0", "b1", "c2", "c1"), result)
    }

    @Test
    fun starsThatDontFitTheBudgetWait() = runTest {
        val stars = listOf(c("s1", "b", 1, starredDay = 1), c("s2", "c", 2, starredDay = 2), c("s3", "a", 3, starredDay = 3), c("s4", "b", 4, starredDay = 4))
        val ordered = EditionPlanner.order(pool + stars, abc, Ordering.TAKE_TURNS)
        val result = EditionPlanner.fill<String>(ordered, PlanRules(Budget.Minutes(20.0), maxPerSource = null), { 10.0 }) { it.id }
        assertEquals(listOf("s3", "s1"), result)
    }

    @Test
    fun aStarFromTheLastSourceInLineStillMakesTheNextEdition() = runTest {
        // Ten sources, room for three: the star mustn't wait for its source's turn to come round.
        val sources = (0 until 10).map { "src$it" }
        val candidates = sources.flatMap { s -> (1..3).map { day -> c("$s-$day", s, day) } } + c("src8-star", "src8", 1, starredDay = 5)
        val ordered = EditionPlanner.order(candidates, sources, Ordering.TAKE_TURNS)
        val result = EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(3), maxPerSource = 1), { 1.0 }) { it.id }
        assertEquals(listOf("src8-star", "src0-3", "src1-3"), result)
    }

    @Test
    fun theSourceFeaturedLongestAgoGoesFirstAndUnknownSourcesGoLast() {
        val withStray = listOf(c("a1", "a", 1), c("b1", "b", 1), c("c1", "c", 1), c("z1", "z", 1))
        assertEquals(listOf("a1", "b1", "c1", "z1"), ids(EditionPlanner.order(withStray, abc, Ordering.TAKE_TURNS)))
        val featured = mapOf("a" to day(3), "c" to day(1))
        // b and z never featured, in the reader's order; then c (day 1), then a (day 3).
        assertEquals(listOf("b1", "z1", "c1", "a1"), ids(EditionPlanner.order(withStray, abc, Ordering.TAKE_TURNS, lastFeatured = featured)))
    }

    @Test
    fun everySourceGetsItsTurnOverAFewEditions() = runTest {
        // Twenty sources, room for five: four editions cover them all rather than sliding by one.
        val sources = (0 until 20).map { "src$it" }
        val featured = mutableMapOf<String, Instant>()
        val seen = mutableSetOf<String>()
        for (edition in 1..4) {
            val candidates = sources.map { c("$it-$edition", it, edition) }
            val ordered = EditionPlanner.order(candidates, sources, Ordering.TAKE_TURNS, lastFeatured = featured)
            val picked = EditionPlanner.fill<Candidate>(ordered, PlanRules(Budget.Articles(5), maxPerSource = 1), { 1.0 }) { it }
            picked.forEach { featured[it.sourceId] = day(edition); seen += it.sourceId }
        }
        assertEquals(sources.toSet(), seen)
    }

    @Test
    fun inOrderAndShuffle() {
        assertEquals(listOf("a3", "a2", "a1", "b1", "c2", "c1"), ids(EditionPlanner.order(pool, abc, Ordering.IN_ORDER)))
        val shuffled = ids(EditionPlanner.order(pool, abc, Ordering.SHUFFLE, random = Random(1)))
        assertEquals(ids(pool).toSet(), shuffled.toSet())
    }

    @Test
    fun emptyPool() {
        assertEquals(emptyList<Candidate>(), EditionPlanner.order(emptyList(), listOf("a"), Ordering.TAKE_TURNS))
    }

    @Test
    fun capsPerSourceAndAFailedFetchLetsTheSourcesNextArticleIn() = runTest {
        val ordered = EditionPlanner.order(pool, abc, Ordering.TAKE_TURNS)
        val result = EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(3), maxPerSource = 1), { 1.0 }) { cand ->
            cand.id.takeUnless { it == "c2" }
        }
        assertEquals(listOf("a3", "b1", "c1"), result)
    }

    @Test
    fun withRoomLeftTheCapGivesWayInTheSameOrder() = runTest {
        // One source, as for a reader who only follows the New Yorker: the cap mustn't make a
        // 30-minute edition out of one article.
        val single = listOf(Candidate("n1", "n", null), Candidate("n2", "n", null), Candidate("n3", "n", null))
        assertEquals(listOf("n1", "n2"), EditionPlanner.fill<String>(single, PlanRules(Budget.Minutes(15.0), maxPerSource = 1), { 10.0 }) { it.id })

        val ordered = EditionPlanner.order(pool, abc, Ordering.TAKE_TURNS)
        assertEquals(
            "every source gets its turn before any gets a second",
            listOf("a3", "b1", "c2", "a2", "c1"),
            EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(5), maxPerSource = 1), { 1.0 }) { it.id },
        )
    }

    @Test
    fun anArticleThatFailedIsNotTriedAgainWhenTheCapGivesWay() = runTest {
        val ordered = EditionPlanner.order(pool, abc, Ordering.TAKE_TURNS)
        val tried = mutableListOf<String>()
        val result = EditionPlanner.fill<String>(ordered, PlanRules(Budget.Articles(10), maxPerSource = 1), { 1.0 }) { cand ->
            tried += cand.id
            cand.id.takeUnless { it == "c2" }
        }
        assertEquals(listOf("a3", "b1", "c1", "a2", "a1"), result)
        assertEquals(1, tried.count { it == "c2" })
    }

    @Test
    fun aSourcesOwnCapReplacesTheEditionsInEitherDirectionAndIsAHardLimit() = runTest {
        val ordered = EditionPlanner.order(pool, abc, Ordering.TAKE_TURNS)
        val rules = PlanRules(Budget.Articles(10), maxPerSource = 2, sourceCaps = mapOf("a" to 3, "c" to 1))
        assertEquals(listOf("a3", "b1", "c2", "a2", "a1"), EditionPlanner.fill<String>(ordered, rules, { 1.0 }) { it.id })
    }

    private data class Fetched(val id: String, val minutes: Double)

    @Test
    fun minutesBudgetStopsAfterAtMostOneArticleOver() = runTest {
        val minutes = linkedMapOf("a3" to 10.0, "b1" to 0.4, "c2" to 25.0, "a2" to 5.0)
        val fetched = mutableListOf<String>()
        val result = EditionPlanner.fill<Fetched>(
            minutes.keys.map { Candidate(it, it.take(1), null) },
            PlanRules(Budget.Minutes(30.0), maxPerSource = null),
            { it.minutes },
        ) { cand -> fetched += cand.id; Fetched(cand.id, minutes.getValue(cand.id)) }
        assertEquals(listOf("a3", "b1", "c2"), result.map { it.id })
        assertEquals("stops fetching once the budget is met", listOf("a3", "b1", "c2"), fetched)
    }

    @Test
    fun articleBudgetSkipsFailedFetches() = runTest {
        val result = EditionPlanner.fill<Fetched>(
            listOf(Candidate("x", "s", null), Candidate("gone", "s", null), Candidate("y", "s", null), Candidate("z", "s", null)),
            PlanRules(Budget.Articles(2), maxPerSource = null),
            { it.minutes },
        ) { cand -> if (cand.id == "gone") null else Fetched(cand.id, 1.0) }
        assertEquals(listOf("x", "y"), result.map { it.id })
    }
}
