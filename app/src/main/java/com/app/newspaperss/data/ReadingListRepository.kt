package com.app.newspaperss.data

import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.feed.ChecklistItem
import com.app.newspaperss.core.feed.MarkdownChecklist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Clock

/**
 * Links saved to read later. The list is a source of its own, so it takes
 * turns with the feeds and gets its own slot in each edition, and its items
 * never expire.
 */
class ReadingListRepository(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    suspend fun sourceId(): Long {
        db.sources().byUrl(URL)?.let { return it.id }
        val id = db.sources().insert(
            SourceEntity(
                kind = SourceKind.READING_LIST, url = URL, title = "Your reading list",
                // Saved links are pages; there's no feed text to fall back on.
                contentMode = ContentMode.PAGE,
                // First in line, so a saved link doesn't wait behind every feed.
                position = -1,
            ),
        )
        return if (id == -1L) db.sources().byUrl(URL)!!.id else id
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<ArticleEntity>> = flow { emit(sourceId()) }.flatMapLatest { db.articles().observeAllForSource(it) }

    /** Returns false if the link was already on the list. */
    suspend fun save(url: String, title: String? = null): Boolean {
        val id = sourceId()
        return db.articles().insertIgnoring(
            ArticleEntity(sourceId = id, guid = url, url = url, title = title?.takeIf { it.isNotBlank() } ?: url, discoveredAt = clock.instant()),
        ) != -1L
    }

    suspend fun remove(article: ArticleEntity) = db.articles().delete(article.id)

    /** Adds the checklist's links; ticked ones arrive as already read. Returns how many were new. */
    suspend fun importMarkdown(text: String): Int {
        val id = sourceId()
        val now = clock.instant()
        return db.articles().insertNew(
            MarkdownChecklist.parse(text).map {
                ArticleEntity(
                    sourceId = id, guid = it.url, url = it.url, title = it.title ?: it.url, discoveredAt = now,
                    state = if (it.done) ArticleState.DELIVERED else ArticleState.NEW,
                )
            },
        )
    }

    /** The list as an rss-to-e-reader checklist: anything no longer waiting is ticked. */
    suspend fun exportMarkdown(): String = MarkdownChecklist.write(
        db.articles().allForSource(sourceId()).map { ChecklistItem(it.url, done = it.state != ArticleState.NEW && it.state != ArticleState.IN_EDITION) },
    )

    companion object {
        /** Not a web address, so it can never collide with a real feed's. */
        const val URL = "newspaperss:reading-list"
    }
}
