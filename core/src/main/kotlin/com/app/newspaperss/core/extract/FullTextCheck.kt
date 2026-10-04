package com.app.newspaperss.core.extract

/** What one article showed about where a source's full text is, and the mode it points to. */
enum class FullTextEvidence(val mode: ContentMode) {
    /** The page had at least twice the feed's words: the feed is a teaser. */
    PAGE_LONGER(ContentMode.PAGE),

    /** The page's article had pictures and the feed's copy of it had none. */
    PAGE_IMAGES(ContentMode.PAGE),

    /** The feed carried the whole article: the page, fetched to compare, had no more. */
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

    /** Long items checked against their page in one edition, for sources still being worked out or due a re-check. */
    const val CHECKS_PER_EDITION = 5

    /** A source settled on its feed's text has one long item checked this often, in case the site starts sending teasers. */
    const val RECHECK_AFTER_DAYS = 14

    /** What [article] says about its source, or null if it doesn't tell (e.g. the page couldn't be reached at all). */
    fun evidence(article: ExtractedArticle): FullTextEvidence? {
        val suggested = ArticleExtractor.suggestMode(article.feedWordCount, article.pageWordCount)
        val compared = article.pageWordCount != null && article.feedWordCount > 0
        return when {
            // Unread for its own reasons (too large, gone, no connection): the feed's text standing
            // in says nothing about whether the page has more.
            article.pageFailure != null -> null
            // A paywall shows only the free part, whatever the feed sends: it says nothing either way.
            article.paidPost -> null
            suggested == ContentMode.PAGE -> FullTextEvidence.PAGE_LONGER
            // Only a page with all of the feed's text: a paywall page with a hero image isn't the better copy.
            compared && (article.pageImageCount ?: 0) > 0 && article.feedImageCount == 0 &&
                article.pageWordCount!! >= article.feedWordCount -> FullTextEvidence.PAGE_IMAGES
            // Only once the page was tried: a long item can still be a teaser. A site that turns the
            // page away but sends whole articles still gives full articles.
            (compared || article.pageBlocked) && article.feedWordCount >= ArticleExtractor.FULL_TEXT_WORDS -> FullTextEvidence.FEED_FULL
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

    /**
     * Whether a long item from a source in [mode] should be checked against its page on [today]:
     * daily while the source is still being worked out, or while settled on its feed's text but
     * the latest [evidence] points to the page, so a change of mind takes three days, not three
     * re-checks; otherwise every [RECHECK_AFTER_DAYS] once settled on the feed. A source settled on
     * the page fetches it anyway.
     */
    fun dueForCheck(mode: ContentMode, evidence: FullTextEvidence?, checkedDay: Long?, today: Long): Boolean = when {
        mode == ContentMode.PAGE -> false
        mode == ContentMode.AUTO || evidence?.mode == ContentMode.PAGE -> checkedDay != today
        else -> checkedDay == null || today - checkedDay >= RECHECK_AFTER_DAYS
    }
}
