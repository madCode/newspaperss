package com.app.newspaperss.core.lists

/**
 * One link a curated list recommends.
 *
 * @param title the list's name for the piece, or null when it only gives a teaser (the page's
 *   own title is used then).
 * @param summary the list's teaser text, if any.
 */
data class ListLink(val url: String, val title: String? = null, val summary: String? = null)

/**
 * The page no longer has the structure a [CuratedList] expects. Links read from a page that
 * changed could be the wrong ones (old picks, navigation), so none are taken.
 */
class ListLayoutChangedException(message: String) : Exception(message)

/**
 * A site that isn't a feed but publishes a daily set of links worth reading, like Arts &
 * Letters Daily. Each list is a source of its own, with its own slot in the edition.
 */
interface CuratedList {
    /** Sources are stored by it (see [CuratedLists.sourceUrl]), so it must never change. */
    val id: String
    val title: String
    /** One line saying what the list offers, shown where it can be added. */
    val blurb: String
    /** The page [links] reads. */
    val pageUrl: String

    /**
     * The newest links on the list's page.
     *
     * @param html the page at [pageUrl].
     * @param baseUrl where the page was fetched from after redirects, to resolve relative links.
     * @throws ListLayoutChangedException when the page doesn't have the expected structure.
     */
    fun links(html: String, baseUrl: String): List<ListLink>
}

object CuratedLists {
    val all: List<CuratedList> = listOf(ArtsAndLettersDaily)

    private const val SOURCE_URL_PREFIX = "newspaperss:list:"

    /**
     * The url a list's source is stored under. It isn't fetchable, so it can't clash with a
     * feed's, and the list's [CuratedList.pageUrl] can change without orphaning the source.
     */
    fun sourceUrl(list: CuratedList) = SOURCE_URL_PREFIX + list.id

    /** The list a source stored under [sourceUrl] reads, or null if this version doesn't have it. */
    fun forSourceUrl(url: String): CuratedList? = all.firstOrNull { sourceUrl(it) == url }
}
