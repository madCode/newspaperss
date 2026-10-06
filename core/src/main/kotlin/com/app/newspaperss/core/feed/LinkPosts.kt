package com.app.newspaperss.core.feed

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.net.creditsSite
import com.app.newspaperss.core.net.registrableDomainOf
import com.app.newspaperss.core.net.siteNameOf
import com.app.newspaperss.core.net.withoutTracking
import org.jsoup.Jsoup

/**
 * Link posts: feed items that are a short pitch for a story on another site, like Longreads'
 * picks (a few paragraphs and "Read the story" at equator.org?src=longreads).
 *
 * Told apart by shape, not by site: the item's own text is short, and it links to another site
 * with a referral tag naming the item's own site. Sites put that tag on the story they're sending
 * readers to; an ordinary post's outbound links rarely carry it.
 */
object LinkPosts {
    /**
     * Longreads' picks run 150-300 words and its own reading lists 1,800 and up; the full-text
     * threshold (300) would miss the longer picks.
     */
    const val MAX_WORDS = 500

    /**
     * The story a link post points to, without tracking parameters, or null if the item isn't one.
     *
     * @param itemUrl the item's own link.
     * @param html the item's content from the feed.
     * @param siteUrl the feed's site, whose name counts as the item's site too (a feed served from
     *   another host); null if unknown.
     */
    fun storyUrl(itemUrl: String, html: String?, siteUrl: String? = null): String? {
        // No query string anywhere, no referral tag: saves parsing every item of every sync.
        if (html.isNullOrBlank() || '?' !in html) return null
        val ownDomains = listOfNotNull(itemUrl, siteUrl).mapNotNull(::registrableDomainOf).toSet()
        val names = siteNames(itemUrl, siteUrl)
        if (ownDomains.isEmpty() || names.isEmpty()) return null
        val body = Jsoup.parseBodyFragment(html, itemUrl).body()
        body.select("[hidden], script, style").remove()
        if (ReadingTime.words(body.text()) >= MAX_WORDS) return null
        val tagged = body.select("a[href]").map { it.absUrl("href") }.filter { href ->
            val domain = registrableDomainOf(href)
            domain != null && domain !in ownDomains && creditsSite(href, names)
        }.map { withoutTracking(it, names) }.distinct()
        // Exactly one: some blogging platforms (Ghost's `ref=`, beehiiv's `utm_source=`) tag every
        // outbound link with the site's name, and a short post of theirs linking to several pages
        // isn't pointing at any one of them.
        return tagged.singleOrNull()
    }

    /**
     * Whether the page a link post points to is the story it pitches: at least half the words of
     * the item's title are in the page's title or address. Pointer sites title a pick with the
     * story's headline; a short commentary post that happens to carry one tagged link is titled
     * for what it says about it. A title with no telling words ("No, But") can't be judged and
     * passes.
     */
    fun isTheStory(itemTitle: String, pageTitle: String?, pageUrl: String): Boolean {
        val wanted = titleWords(itemTitle)
        if (wanted.isEmpty()) return true
        val path = runCatching { java.net.URI(pageUrl).path }.getOrNull().orEmpty()
        val found = titleWords(pageTitle.orEmpty()) + titleWords(path)
        val matched = wanted.count { w -> found.any { f -> sameWord(w, f) } }
        return matched * 2 >= wanted.size
    }

    private fun titleWords(text: String): Set<String> =
        text.lowercase().replace(APOSTROPHES, "").split(NOT_WORD)
            .filter { it.length >= 3 && it !in STOP_WORDS && !it.all(Char::isDigit) }.toSet()

    // "undertaker" and "undertakers": a shared start of five letters or more counts.
    private fun sameWord(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 5 && (a.startsWith(b) || b.startsWith(a)))

    private val APOSTROPHES = Regex("['’]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val STOP_WORDS = (
        "the and for with from that this what when where who why how are was were you your our its his her their not but " +
            "all any can has have had one out about into over after before than then them they there these those some just " +
            "more most very will would could should been being also only even such off per via"
        ).split(' ').toSet()

    /** The names [withoutTracking] should treat as the item's own site, for [itemUrl] from a feed of [siteUrl]. */
    fun siteNames(itemUrl: String, siteUrl: String? = null): Set<String> =
        listOfNotNull(itemUrl, siteUrl).mapNotNull(::siteNameOf).toSet()
}
