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
            newestIn(header.parent()!!, name)
        }.distinctBy { it.url }
    }

    /**
     * Only the column's first paragraph counts: if it isn't a teaser with a link, the column
     * changed shape, and the next paragraph down would be an older pick.
     */
    private fun newestIn(column: Element, name: String): ListLink {
        val entry = column.children().firstOrNull { it.tagName() == "p" }
            ?: throw ListLayoutChangedException("no entries in \"$name\"")
        val link = entry.select("a[href]").lastOrNull()?.takeIf { it.text().startsWith("more") }
        val url = link?.absUrl("href")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw ListLayoutChangedException("the newest entry in \"$name\" has no link")
        val teaser = entry.clone().apply { select("a[href]").last()?.remove() }
            .text().replace(' ', ' ').trim()
        return ListLink(url, title = null, summary = teaser.ifEmpty { null })
    }
}
