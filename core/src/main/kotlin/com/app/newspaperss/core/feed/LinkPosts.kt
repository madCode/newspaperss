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
     * Parameters
     * ----------
     * itemUrl: the item's own link.
     * html: the item's content from the feed.
     * siteUrl: the feed's site, whose name counts as the item's site too (a feed served from
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

    /** The names [withoutTracking] should treat as the item's own site, for [itemUrl] from a feed of [siteUrl]. */
    fun siteNames(itemUrl: String, siteUrl: String? = null): Set<String> =
        listOfNotNull(itemUrl, siteUrl).mapNotNull(::siteNameOf).toSet()
}
