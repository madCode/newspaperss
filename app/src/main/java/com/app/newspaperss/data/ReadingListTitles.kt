package com.app.newspaperss.data

import com.app.newspaperss.core.extract.PageTitle
import com.app.newspaperss.core.net.HttpClient
import java.io.IOException

/**
 * Looks up the page titles of saved links that arrived without one, so the
 * reading list shows a headline instead of a bare domain before the link
 * reaches an edition.
 */
class ReadingListTitles(private val db: AppDatabase, private val http: HttpClient) {
    /** Returns false if a page couldn't be reached, so the lookup is worth trying again later. */
    suspend fun fetch(articleIds: List<Long>): Boolean {
        var unreachable = false
        for (id in articleIds) {
            val article = db.articles().byId(id) ?: continue
            if (article.title.isNotEmpty() || article.state != ArticleState.NEW) continue
            val page = try {
                http.get(article.url)
            } catch (e: IOException) {
                unreachable = true
                continue
            }
            if (!page.isSuccessful || page.contentType?.contains("html", ignoreCase = true) == false) continue
            val title = PageTitle.of(page.body, page.finalUrl) ?: continue
            // Checked again in the update: an edition may have taken the article while the page loaded.
            db.articles().setTitleIfUntitled(id, title)
        }
        return !unreachable
    }
}
