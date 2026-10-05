package com.app.newspaperss.ui.today

import com.app.newspaperss.data.EditionArticleEntity

/**
 * What Today's card shows of the latest edition: a lead story, the next few in the book's order,
 * and how many more there are.
 */
data class FrontPage(
    val lead: EditionArticleEntity,
    /** Why [lead] leads; null when it's simply the first article. */
    val why: Why?,
    val next: List<EditionArticleEntity>,
    val more: Int,
) {
    enum class Why { STARRED, LONGEST }

    companion object {
        /**
         * The lead is what the reader starred (the first star in the book, which the planner put
         * there as the oldest), otherwise the longest read. A longest read that only ties another
         * isn't a reason to lead, so then it's the first article, as the book opens.
         */
        fun of(articles: List<EditionArticleEntity>, next: Int = 2): FrontPage? {
            val inOrder = articles.sortedBy { it.position }
            val first = inOrder.firstOrNull() ?: return null
            val starred = inOrder.firstOrNull { it.starred }
            val longest = inOrder.maxBy { it.minutes }.takeIf { top -> inOrder.none { it !== top && it.minutes >= top.minutes } }
            val (lead, why) = when {
                starred != null -> starred to Why.STARRED
                longest != null && inOrder.size > 1 -> longest to Why.LONGEST
                else -> first to null
            }
            val rest = inOrder.filter { it !== lead }
            return FrontPage(lead, why, rest.take(next), (rest.size - next).coerceAtLeast(0))
        }
    }
}
