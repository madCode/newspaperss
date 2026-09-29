package com.app.newspaperss.core.extract

import org.jsoup.Jsoup

/** A page's headline without extracting its article, for showing a saved link before it's in an edition. */
object PageTitle {
    /** The og:title, twitter:title or `<title>`, without a trailing site name; null if the page has none. */
    fun of(html: String, url: String): String? {
        val doc = Jsoup.parse(html, url)
        fun meta(name: String) = doc.selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")?.attr("content")
        val raw = listOfNotNull(meta("og:title"), meta("twitter:title"), doc.title()).firstOrNull { it.isNotBlank() } ?: return null
        return PageExtractor.cleanTitle(raw, siteName = meta("og:site_name").orEmpty(), url = url).ifBlank { null }
    }
}

/** How long a page's article is, for a reading-time estimate before the article is fetched for an edition. */
object PageWords {
    fun of(html: String, url: String): Int = PageExtractor.extract(html, url).wordCount
}
