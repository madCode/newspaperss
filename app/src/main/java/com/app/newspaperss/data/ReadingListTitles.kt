package com.app.newspaperss.data

import com.app.newspaperss.core.extract.PageTitle
import com.app.newspaperss.core.extract.PageWords
import com.app.newspaperss.core.net.HttpClient
import java.io.IOException

/**
 * Looks up saved links' pages before they reach an edition: the title of one
 * that arrived without one, so the reading list shows a headline instead of a
 * bare domain, and every one's length, for its reading time.
 */
class ReadingListTitles(private val db: AppDatabase, private val http: HttpClient) {
    /** Returns false if a page couldn't be reached, so the lookup is worth trying again later. */
    suspend fun fetch(articleIds: List<Long>): Boolean {
        var unreachable = false
        for (id in articleIds) {
            val article = db.articles().byId(id) ?: continue
            if (article.state != ArticleState.NEW || (article.title.isNotEmpty() && article.pageWords != null)) continue
            val page = try {
                http.get(article.url)
            } catch (e: IOException) {
                unreachable = true
                continue
            }
            if (!page.isSuccessful || page.contentType?.contains("html", ignoreCase = true) == false) continue
            // One page the extractor can't handle mustn't fail the batch: the worker's failure
            // would cancel the batches queued behind it.
            val measured = try {
                PageWords.of(page.body, page.finalUrl)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            measured?.let { db.articles().setPageWords(id, it) }
            val title = PageTitle.of(page.body, page.finalUrl) ?: continue
            // Checked again in the update: an edition may have taken the article while the page loaded.
            db.articles().setTitleIfUntitled(id, title)
        }
        return !unreachable
    }
}
