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
 * @param streak how many articles in a row, ending with [evidence], pointed to [evidence]'s mode.
 */
data class FullTextState(val mode: ContentMode, val evidence: FullTextEvidence?, val streak: Int)

/**
 * The per-source full-text check, run on every article instead of asking the
 * reader: once [SETTLE_AFTER] articles in a row point to the same mode, the
 * source switches to it. A mixed source never gets that run and stays as it is.
 */
object FullTextCheck {
    const val SETTLE_AFTER = 3

    /** What [article] says about its source, or null if it doesn't tell (e.g. the page couldn't be reached at all). */
    fun evidence(article: ExtractedArticle): FullTextEvidence? {
        val suggested = ArticleExtractor.suggestMode(article.feedWordCount, article.pageWordCount)
        return when {
            suggested == ContentMode.PAGE -> FullTextEvidence.PAGE_LONGER
            article.usedFeedContent && article.feedWordCount >= ArticleExtractor.FULL_TEXT_WORDS -> FullTextEvidence.FEED_FULL
            suggested == ContentMode.FEED -> FullTextEvidence.FEED_SHORT
            article.pageBlocked -> FullTextEvidence.BLOCKED
            else -> null
        }
    }

    fun next(state: FullTextState, evidence: FullTextEvidence): FullTextState {
        val streak = if (state.evidence?.mode == evidence.mode) state.streak + 1 else 1
        return FullTextState(if (streak >= SETTLE_AFTER) evidence.mode else state.mode, evidence, streak)
    }
}
