package com.app.newspaperss.core.extract

import org.jsoup.Jsoup

/** A page's headline without extracting its article, for showing a saved link before it's in an edition. */
object PageTitle {
    /** The title an edition would give the page (see [PageExtractor.title]); null if the page has none. */
    fun of(html: String, url: String): String? {
        val doc = Jsoup.parse(html, url)
        val siteName = doc.selectFirst("meta[property=og:site_name], meta[name=og:site_name]")?.attr("content").orEmpty()
        return PageExtractor.title(doc, siteName = siteName, url = url)?.ifBlank { null }
    }
}

/** How long a page's article is, for a reading-time estimate before the article is fetched for an edition. */
object PageWords {
    fun of(html: String, url: String): Int = PageExtractor.extract(html, url).wordCount
}
