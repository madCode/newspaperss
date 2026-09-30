package com.app.newspaperss.data

import androidx.room.withTransaction
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.feed.ChecklistItem
import com.app.newspaperss.core.feed.MarkdownChecklist
import com.app.newspaperss.core.feed.ReadLaterImport
import com.app.newspaperss.core.feed.ReadingListFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * Links saved to read later. The list is a source of its own, so it takes
 * turns with the feeds and gets its own slot in each edition, and its items
 * never expire.
 *
 * [onUntitled] is given the ids of links to look up in the background (see
 * [ReadingListTitles]): each link saved, for its length, and imported links
 * without a title.
 */
class ReadingListRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val onUntitled: (articleIds: List<Long>) -> Unit = {},
) {
    suspend fun sourceId(): Long {
        db.sources().byUrl(URL)?.let { return it.id }
        val id = db.sources().insert(
            SourceEntity(
                kind = SourceKind.READING_LIST, url = URL, title = "Your reading list",
                // Saved links are pages; there's no feed text to fall back on.
                contentMode = ContentMode.PAGE,
                // First in line, so a saved link doesn't wait behind every feed.
                position = -1,
                section = SECTION,
            ),
        )
        return if (id == -1L) db.sources().byUrl(URL)!!.id else id
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<ArticleEntity>> = flow { emit(sourceId()) }.flatMapLatest { db.articles().observeAllForSource(it) }

    /** How many saved links are still waiting for an edition: read or archived ones aren't. */
    fun observeWaiting(): Flow<Int> = observe().map { list -> list.count { it.state == ArticleState.NEW } }

    /** Returns false if the link was already on the list. */
    suspend fun save(url: String, title: String? = null): Boolean {
        val article = ArticleEntity(sourceId = sourceId(), guid = url, url = url, title = title?.trim().orEmpty(), discoveredAt = clock.instant())
        val articleId = db.articles().insertIgnoring(article)
        if (articleId == -1L) return false
        onUntitled(listOf(articleId))
        return true
    }

    suspend fun remove(article: ArticleEntity) = db.articles().delete(article.id)

    /** @property unread how many of the [added] links are waiting for an edition (archived ones aren't). */
    data class Imported(val format: ReadingListFile.Format, val added: Int, val unread: Int = added)

    /**
     * Adds the links from a markdown checklist or a Pocket or Instapaper export.
     * Ticked or archived ones arrive as already read, and a link still waiting
     * here that the file marks read (read elsewhere) is marked read too.
     */
    suspend fun import(text: String): Imported {
        val file = ReadLaterImport.parse(text)
        val id = sourceId()
        val now = clock.instant()
        val added = db.withTransaction {
            file.items.mapNotNull { item ->
                val article = ArticleEntity(
                    sourceId = id, guid = item.url, url = item.url, title = item.title.orEmpty(), discoveredAt = now,
                    state = if (item.done) ArticleState.DELIVERED else ArticleState.NEW,
                )
                db.articles().insertIgnoring(article).takeIf { it != -1L }?.let { article.copy(id = it) }
            }
        }
        val done = file.items.filter { it.done }.map { it.url }.toSet()
        val readElsewhere = db.articles().allForSource(id).filter { it.state == ArticleState.NEW && it.url in done }
        if (readElsewhere.isNotEmpty()) db.articles().setState(readElsewhere.map { it.id }, ArticleState.DELIVERED)
        val untitled = added.filter { it.title.isEmpty() && it.state == ArticleState.NEW }.map { it.id }
        if (untitled.isNotEmpty()) onUntitled(untitled)
        return Imported(file.format, added.size, added.count { it.state == ArticleState.NEW })
    }

    /** The list as an rss-to-e-reader checklist: anything no longer waiting is ticked. */
    suspend fun exportMarkdown(): String = MarkdownChecklist.write(
        db.articles().allForSource(sourceId()).map { ChecklistItem(it.url, done = it.state != ArticleState.NEW && it.state != ArticleState.IN_EDITION) },
    )

    companion object {
        /** Not a web address, so it can never collide with a real feed's. */
        const val URL = "newspaperss:reading-list"
        const val SECTION = "Saved for later"
    }
}
