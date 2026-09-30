package com.app.newspaperss.core.extract

/** What one article showed about where a source's full text is, and the mode it points to. */
enum class FullTextEvidence(val mode: ContentMode) {
    /** The page had at least twice the feed's words: the feed is a teaser. */
    PAGE_LONGER(ContentMode.PAGE),

    /** The feed carried the whole article. */
    FEED_FULL(ContentMode.FEED),

    /** The feed's text was short, but extraction found even less on the page. */
    FEED_SHORT(ContentMode.FEED),

    /** The site turned the page request away, so the feed's text is all there is. */
    BLOCKED(ContentMode.FEED),
}

/**
 * A source's content mode and the run of evidence behind it.
 *
 * @param streak how many days in a row, ending with [evidence], pointed to [evidence]'s mode.
 * @param day the epoch day [evidence] was last counted.
 */
data class FullTextState(val mode: ContentMode, val evidence: FullTextEvidence?, val streak: Int, val day: Long? = null)

/**
 * The per-source full-text check, run on every article instead of asking the
 * reader: once [SETTLE_AFTER] days in a row point to the same mode, the source
 * switches to it. A mixed source never gets that run and stays as it is.
 *
 * Counted per day, not per article: a site that shows bot checks for one
 * morning's three articles would otherwise be settled by a single bad build.
 */
object FullTextCheck {
    const val SETTLE_AFTER = 3

    /** What [article] says about its source, or null if it doesn't tell (e.g. the page couldn't be reached at all). */
    fun evidence(article: ExtractedArticle): FullTextEvidence? {
        val suggested = ArticleExtractor.suggestMode(article.feedWordCount, article.pageWordCount)
        return when {
            // Unread for its own reasons (too large, gone, no connection): the feed's text standing
            // in says nothing about whether the page has more.
            article.pageFailure != null -> null
            suggested == ContentMode.PAGE -> FullTextEvidence.PAGE_LONGER
            article.usedFeedContent && article.feedWordCount >= ArticleExtractor.FULL_TEXT_WORDS -> FullTextEvidence.FEED_FULL
            suggested == ContentMode.FEED -> FullTextEvidence.FEED_SHORT
            // Blocked with no feed text says nothing: the page was the only text there was.
            article.pageBlocked && article.feedWordCount > 0 -> FullTextEvidence.BLOCKED
            else -> null
        }
    }

    fun next(state: FullTextState, evidence: FullTextEvidence, day: Long): FullTextState {
        val sameWay = state.evidence?.mode == evidence.mode
        if (sameWay && state.day == day) return state
        val streak = if (sameWay) state.streak + 1 else 1
        return FullTextState(if (streak >= SETTLE_AFTER) evidence.mode else state.mode, evidence, streak, day)
    }
}
