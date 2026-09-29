package com.app.newspaperss.core.edition

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class EditionPlannerTest {
    private fun c(id: String, source: String, day: Int, back: Boolean = false) =
        Candidate(id, source, Instant.parse("2026-09-%02dT00:00:00Z".format(day)), back)

    private val pool = listOf(
        c("a1", "a", 1), c("a2", "a", 2), c("a3", "a", 3),
        c("b1", "b", 1),
        c("c1", "c", 1), c("c2", "c", 2),
    )

    private fun ids(list: List<Candidate>) = list.map { it.id }

    @Test
    fun takesTurnsNewestFirstWithinEachSource() {
        val rules = PlanRules(Budget.Articles(10), maxPerSource = null)
        assertEquals(
            listOf("a3", "b1", "c2", "a2", "c1", "a1"),
            ids(EditionPlanner.order(pool, listOf("a", "b", "c"), rules)),
        )
    }

    @Test
    fun capsPerSource() {
        val rules = PlanRules(Budget.Articles(10), maxPerSource = 1)
        assertEquals(listOf("a3", "b1", "c2"), ids(EditionPlanner.order(pool, listOf("a", "b", "c"), rules)))
    }

    @Test
    fun broughtBackArticlesGoFirstInTheirSource() {
        val withBack = pool + c("a0", "a", 1, back = true)
        val rules = PlanRules(Budget.Articles(10), maxPerSource = 1)
        assertEquals(listOf("a0", "b1", "c2"), ids(EditionPlanner.order(withBack, listOf("a", "b", "c"), rules)))
    }

    @Test
    fun rotationChangesWhoGoesFirstAndUnknownSourcesGoLast() {
        val rules = PlanRules(Budget.Articles(10), maxPerSource = 1)
        val withStray = pool + c("z1", "z", 1)
        assertEquals(listOf("b1", "c2", "z1", "a3"), ids(EditionPlanner.order(withStray, listOf("a", "b", "c"), rules, rotation = 1)))
        assertEquals(listOf("a3", "b1", "c2", "z1"), ids(EditionPlanner.order(withStray, listOf("a", "b", "c"), rules, rotation = 4)))
    }

    @Test
    fun inOrderAndShuffle() {
        val inOrder = PlanRules(Budget.Articles(10), maxPerSource = 2, ordering = Ordering.IN_ORDER)
        assertEquals(listOf("a3", "a2", "b1", "c2", "c1"), ids(EditionPlanner.order(pool, listOf("a", "b", "c"), inOrder)))
        val shuffle = inOrder.copy(ordering = Ordering.SHUFFLE)
        val shuffled = ids(EditionPlanner.order(pool, listOf("a", "b", "c"), shuffle, random = Random(1)))
        assertEquals(setOf("a3", "a2", "b1", "c2", "c1"), shuffled.toSet())
    }

    @Test
    fun emptyPool() {
        assertEquals(emptyList<Candidate>(), EditionPlanner.order(emptyList(), listOf("a"), PlanRules(Budget.Articles(3)), rotation = 5))
    }

    private data class Fetched(val id: String, val minutes: Double)

    @Test
    fun minutesBudgetStopsAfterAtMostOneArticleOver() = runTest {
        val minutes = linkedMapOf("a3" to 10.0, "b1" to 0.4, "c2" to 25.0, "a2" to 5.0)
        val fetched = mutableListOf<String>()
        val result = EditionPlanner.fill<Fetched>(
            minutes.keys.map { Candidate(it, it.take(1), null) },
            Budget.Minutes(30.0),
            { it.minutes },
        ) { cand -> fetched += cand.id; Fetched(cand.id, minutes.getValue(cand.id)) }
        assertEquals(listOf("a3", "b1", "c2"), result.map { it.id })
        assertEquals("stops fetching once the budget is met", listOf("a3", "b1", "c2"), fetched)
    }

    @Test
    fun articleBudgetSkipsFailedFetches() = runTest {
        val result = EditionPlanner.fill<Fetched>(
            listOf(Candidate("x", "s", null), Candidate("gone", "s", null), Candidate("y", "s", null), Candidate("z", "s", null)),
            Budget.Articles(2),
            { it.minutes },
        ) { cand -> if (cand.id == "gone") null else Fetched(cand.id, 1.0) }
        assertEquals(listOf("x", "y"), result.map { it.id })
    }
}
