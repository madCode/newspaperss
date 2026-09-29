package com.app.newspaperss.edition

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import org.jsoup.Jsoup

/** Uses only the text the feed itself carries; no page fetching. */
class FeedContentProvider(private val wpm: Int = ReadingTime.DEFAULT_WPM) : ArticleContentProvider {
    override suspend fun contentFor(article: ArticleEntity, source: SourceEntity): ArticleContent? {
        val html = article.feedHtml?.takeIf { it.isNotBlank() } ?: return null
        val words = Jsoup.parse(html).text().split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words == 0) return null
        return ArticleContent(article.title, article.author, html, ReadingTime.minutes(words, wpm))
    }
}
