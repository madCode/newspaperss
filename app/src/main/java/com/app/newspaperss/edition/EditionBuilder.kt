package com.app.newspaperss.edition

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Candidate
import com.app.newspaperss.core.edition.EditionPlanner
import com.app.newspaperss.core.edition.EditionTitles
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.images.ImageBudget
import com.app.newspaperss.core.images.ImageRules
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import androidx.room.withTransaction
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

sealed interface BuildResult {
    data class Built(val editionId: Long) : BuildResult
    /** Nothing new to read; no edition was made. */
    data object NothingNew : BuildResult
    data class Failed(val editionId: Long, val reason: String) : BuildResult
}

class EditionBuilder(
    private val db: AppDatabase,
    private val content: ArticleContentProvider,
    private val editionsDir: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val imageBudgetBytes: Long = ImageRules.MAX_EDITION_BYTES,
) {
    suspend fun build(settings: EditionSettings, onProgress: (done: Int) -> Unit = {}): BuildResult {
        releaseUndelivered()
        val sources = db.sources().all().filter { !it.paused }
        val sourcesById = sources.associateBy { it.id }
        val articles = db.articles().candidates().filter { it.sourceId in sourcesById }
        if (articles.isEmpty()) return BuildResult.NothingNew
        val byId = articles.associateBy { it.id }

        val now = LocalDateTime.now(clock.withZone(zone))
        val startOfDay = now.toLocalDate().atStartOfDay(zone).toInstant()
        val title = EditionTitles.title(now, db.editions().titlesSince(startOfDay))
        val rotation = db.editions().count()
        val editionId = db.editions().insert(EditionEntity(title = title, createdAt = clock.instant()))

        val ordered = EditionPlanner.order(
            candidates = articles.map { Candidate(it.id.toString(), it.sourceId.toString(), it.published ?: it.discoveredAt, it.broughtBack) },
            sourceOrder = sources.map { it.id.toString() },
            ordering = settings.ordering,
            rotation = rotation,
        )
        var fetched = 0
        fun minutesOf(c: ArticleContent) = ReadingTime.minutes(c.wordCount, settings.wordsPerMinute)
        val picked = EditionPlanner.fill<Pair<ArticleEntity, ArticleContent>>(ordered, settings.rules, { minutesOf(it.second) }) { c ->
            val article = byId.getValue(c.id.toLong())
            val result = content.contentFor(article, sourcesById.getValue(article.sourceId))?.let { article to it }
            onProgress(++fetched)
            result
        }
        if (picked.isEmpty()) return fail(editionId, "None of the articles could be read.")

        // Which articles made it is the planner's call; reading order is the reader's
        // own: sections in the order they first appear, sources in list order within them.
        val sectionOrder = sources.map { it.section }.distinct()
        val sourceIndex = sources.withIndex().associate { (i, s) -> s.id to i }
        val arranged = picked.sortedWith(
            compareBy<Pair<ArticleEntity, ArticleContent>>(
                { (a, _) -> sectionOrder.indexOf(sourcesById.getValue(a.sourceId).section) },
                { (a, _) -> sourceIndex.getValue(a.sourceId) },
            ),
        )

        // Budgeted in reading order, so the first articles keep their pictures. EpubWriter drops
        // an img whose image isn't in the book, taking an emptied figure with it.
        val fitted = ImageBudget.fit(arranged.map { it.second.images }, imageBudgetBytes)
        val withImages = arranged.zip(fitted) { (a, c), images -> a to c.copy(images = images) }

        val fileName = "edition-$editionId.epub"
        try {
            editionsDir.mkdirs()
            val doc = EditionDoc(
                title = title,
                date = now.toLocalDate(),
                identifier = "urn:uuid:${UUID.randomUUID()}",
                sections = withImages.groupBy { (a, _) -> sourcesById.getValue(a.sourceId).section }.map { (section, items) ->
                    EditionSection(section, items.map { (a, c) -> toEpub(a, c, minutesOf(c), sourcesById.getValue(a.sourceId)) })
                },
                modified = clock.instant(),
            )
            File(editionsDir, fileName).outputStream().use { EpubWriter.write(doc, it) }
        } catch (e: Exception) {
            return fail(editionId, "Couldn't write the edition: ${e.message}")
        }

        db.withTransaction {
            db.editions().insertArticles(
                arranged.mapIndexed { i, (a, c) ->
                    EditionArticleEntity(editionId = editionId, articleId = a.id, position = i, title = c.title, sourceTitle = sourcesById.getValue(a.sourceId).title, minutes = minutesOf(c))
                },
            )
            db.articles().setState(arranged.map { it.first.id }, ArticleState.IN_EDITION)
            db.editions().update(
                db.editions().byId(editionId)!!.copy(
                    status = EditionStatus.READY, fileName = fileName,
                    articleCount = arranged.size, minutes = arranged.sumOf { minutesOf(it.second) },
                ),
            )
        }
        return BuildResult.Built(editionId)
    }

    /**
     * An edition still READY when the next one is built was never confirmed as
     * delivered, so its articles go back in the pool, first in line.
     */
    private suspend fun releaseUndelivered() {
        val editions = db.editions()
        for (edition in editions.withStatus(EditionStatus.READY)) {
            db.withTransaction {
                db.articles().bringBack(editions.articleIds(edition.id))
                editions.update(edition.copy(status = EditionStatus.FAILED, error = "Not sent; its articles went into the next edition."))
            }
        }
    }

    private suspend fun fail(editionId: Long, reason: String): BuildResult {
        db.editions().update(db.editions().byId(editionId)!!.copy(status = EditionStatus.FAILED, error = reason))
        return BuildResult.Failed(editionId, reason)
    }

    private fun toEpub(a: ArticleEntity, c: ArticleContent, minutes: Double, source: SourceEntity) = EditionArticle(
        title = c.title,
        sourceTitle = source.title,
        url = a.url,
        bodyHtml = c.bodyHtml,
        minutes = minutes,
        author = c.author,
        published = a.published?.atZone(zone)?.toLocalDate(),
        note = c.note,
        images = c.images,
    )
}
