package com.app.newspaperss.core.lists

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * aldaily.com: three columns (Articles of Note, New Books, Essays & Opinions), each a run of
 * teaser paragraphs ending in a "more »" link, newest at the top. The newest entry of each
 * column is that day's pick.
 */
object ArtsAndLettersDaily : CuratedList {
    override val id = "aldaily"
    override val title = "Arts & Letters Daily"
    override val blurb = "Three picks a day from essays and reviews"
    override val pageUrl = "https://www.aldaily.com/"

    private val COLUMNS = listOf("Articles of Note", "New Books", "Essays & Opinions")

    override fun links(html: String, baseUrl: String): List<ListLink> {
        val headers = Jsoup.parse(html, baseUrl).select("h2.column_headers")
        return COLUMNS.map { name ->
            val header = headers.firstOrNull { it.text() == name } ?: throw ListLayoutChangedException("no \"$name\" column")
            newestIn(header, name)
        }.distinctBy { it.url }
    }

    /**
     * The newest entry runs from the column's header to its "more »" link. It's read as a run of
     * nodes rather than one `<p>` because the site sometimes nests a `<p>` in the teaser, which
     * the HTML parser splits into several siblings. The run also stops at an `<hr>`: an entry
     * that ends without its link means the column changed shape, and carrying on would take the
     * next entry down, an older pick.
     */
    private fun newestIn(header: Element, name: String): ListLink {
        val entry = Element("div").also { it.setBaseUri(header.baseUri()) }
        var node = header.nextSibling()
        while (node != null && !(node is Element && node.tagName() == "hr")) {
            entry.appendChild(node.clone())
            if (moreLink(entry) != null) break
            node = node.nextSibling()
        }
        if (entry.text().isBlank()) throw ListLayoutChangedException("no entries in \"$name\"")
        val link = moreLink(entry)
        val url = link?.absUrl("href")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw ListLayoutChangedException("the newest entry in \"$name\" has no link")
        link.remove()
        val teaser = entry.text().replace('\u00A0', ' ').trim()
        return ListLink(url, title = null, summary = teaser.ifEmpty { null })
    }

    private fun moreLink(entry: Element): Element? = entry.select("a[href]").firstOrNull { it.text().startsWith("more") }
}
