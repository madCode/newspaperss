package com.app.newspaperss.edition

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Candidate
import com.app.newspaperss.core.images.ImageAllowance
import com.app.newspaperss.core.edition.EditionPlanner
import com.app.newspaperss.core.edition.EditionTitles
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.plural
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
import com.app.newspaperss.data.SourceKind
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

sealed interface BuildResult {
    data class Built(val editionId: Long) : BuildResult
    /** Nothing new to read; no edition was made. */
    data object NothingNew : BuildResult
    data class Failed(val editionId: Long, val reason: String) : BuildResult
    /** Nothing to make it from because none of the [sources] could be read; no edition was made. */
    data class Unreachable(val sources: Int) : BuildResult {
        // Not "check your connection": a timed run only starts once there is one, so the usual
        // causes are a tt-rss sign-in, a feed that moved, a list whose page changed.
        val reason get() = "None of your ${plural(sources, "source")} could be read. Sources shows what went wrong with each."
    }
}

/**
 * Picks, fetches and writes the next edition.
 *
 * @param cover draws the edition's cover image, or returns null for a text-only cover page.
 */
class EditionBuilder(
    private val db: AppDatabase,
    private val content: ArticleContentProvider,
    private val editionsDir: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val imageBudgetBytes: Long = ImageRules.MAX_EDITION_BYTES,
    private val cover: (CoverInfo) -> EpubImage? = { null },
) {
    suspend fun build(settings: EditionSettings, dueAt: Instant? = null, onProgress: (done: Int) -> Unit = {}): BuildResult {
        failInterrupted()
        releaseUndelivered()
        val sources = db.sources().all().filter { !it.paused }
        val sourcesById = sources.associateBy { it.id }
        // The same link from two sources goes in once, and a starred copy is the one kept.
        val articles = db.articles().candidates().filter { it.sourceId in sourcesById }
            .sortedBy { it.starredAt == null }
            .distinctBy { it.url.ifBlank { "#${it.id}" } }
        if (articles.isEmpty()) return BuildResult.NothingNew

        // A timed edition is built ahead of its time; it's titled and dated for when it's due.
        val now = LocalDateTime.ofInstant(dueAt ?: clock.instant(), zone)
        // From the day before: an edition due just after midnight was made just before it.
        // Titles carry their date, so yesterday's can't clash.
        val since = now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant()
        val title = EditionTitles.title(now, db.editions().titlesSince(since))
        val rotation = db.editions().count()
        val editionId = db.editions().insert(EditionEntity(title = title, createdAt = clock.instant()))

        // Whatever goes wrong from here, the edition must not stay BUILDING: the Today screen
        // would show it as being made forever. Its articles only change state in the final
        // transaction, so a failed edition leaves them all for the next one.
        return try {
            fill(editionId, title, now, sources, articles, rotation, settings, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { fail(editionId, STOPPED) }
            throw e
        } catch (e: Exception) {
            fail(editionId, UNEXPECTED)
        } catch (e: OutOfMemoryError) {
            // Plausible on low-memory e-readers with image-heavy articles; the next build may fit.
            fail(editionId, UNEXPECTED)
        }
    }

    private suspend fun fill(
        editionId: Long,
        title: String,
        now: LocalDateTime,
        sources: List<SourceEntity>,
        articles: List<ArticleEntity>,
        rotation: Int,
        settings: EditionSettings,
        onProgress: (done: Int) -> Unit,
    ): BuildResult {
        val sourcesById = sources.associateBy { it.id }
        val byId = articles.associateBy { it.id }
        // An aggregator's publications take turns and are capped one by one, like feeds of their
        // own, and together they take the aggregator's place in the reader's source order.
        val publicationOrder = sources.flatMap { s ->
            if (s.kind == SourceKind.TTRSS) articles.filter { it.sourceId == s.id }.map(::publicationOf).distinct().sorted()
            else listOf(s.id.toString())
        }
        val ordered = EditionPlanner.order(
            candidates = articles.map { Candidate(it.id.toString(), publicationOf(it), it.published ?: it.discoveredAt, it.starredAt) },
            sourceOrder = publicationOrder,
            ordering = settings.ordering,
            rotation = rotation,
        )
        var fetched = 0
        val allowance = ImageAllowance(imageBudgetBytes)
        fun minutesOf(c: ArticleContent) = ReadingTime.minutes(c.wordCount, settings.wordsPerMinute)
        // tt-rss candidates are keyed by publication, so an account-wide cap wouldn't match any of them.
        val caps = sources.filter { it.kind != SourceKind.TTRSS }.mapNotNull { s -> s.maxArticles?.let { s.id.toString() to it } }.toMap()
        val rules = settings.rules.copy(sourceCaps = caps)
        val picked = EditionPlanner.fill<Pair<ArticleEntity, ArticleContent>>(ordered, rules, { minutesOf(it.second) }) { c ->
            val article = byId.getValue(c.id.toLong())
            val result = try {
                content.contentFor(article, sourcesById.getValue(article.sourceId), allowance)?.let { article to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One broken page shouldn't cost the reader the whole edition. Only the exception
                // type is logged: messages can carry the article's URL.
                Log.w(TAG, "Skipped an article: ${e.javaClass.name}")
                null
            }
            onProgress(++fetched)
            result
        }
        if (picked.isEmpty()) return fail(editionId, "None of the articles could be read.")

        // Which articles made it is the planner's call; reading order is the reader's
        // own: sections in the order they first appear, sources in list order within them.
        val sectionOrder = sources.map { it.section }.distinct()
        val sourceIndex = sources.withIndex().associate { (i, s) -> s.id to i }
        val pickedPublications = picked.map { publicationOf(it.first) }.distinct()
        val arranged = picked.sortedWith(
            compareBy<Pair<ArticleEntity, ArticleContent>>(
                { (a, _) -> sectionOrder.indexOf(sourcesById.getValue(a.sourceId).section) },
                { (a, _) -> sourceIndex.getValue(a.sourceId) },
                { (a, _) -> pickedPublications.indexOf(publicationOf(a)) },
            ),
        )

        val totalMinutes = arranged.sumOf { minutesOf(it.second) }
        val coverImage = coverFor(
            CoverInfo(
                title = title,
                date = now.toLocalDate(),
                headlines = arranged.map { (a, c) -> CoverHeadline(c.title, bylineOf(a, sourcesById.getValue(a.sourceId))) },
                articleCount = arranged.size,
                minutes = totalMinutes,
            ),
        )

        // Budgeted again in reading order (fetch order differs), so the first articles keep their
        // pictures. EpubWriter drops an img whose image isn't in the book, and an emptied figure.
        val articleImageBudget = (imageBudgetBytes - (coverImage?.bytes?.size ?: 0)).coerceAtLeast(0)
        val fitted = ImageBudget.fit(arranged.map { it.second.images }, articleImageBudget)
        val withImages = arranged.zip(fitted) { (a, c), images -> a to c.copy(images = images) }

        val fileName = fileNameOf(editionId)
        val doc = EditionDoc(
            title = title,
            date = now.toLocalDate(),
            identifier = "urn:uuid:${UUID.randomUUID()}",
            sections = withImages.groupBy { (a, _) -> sourcesById.getValue(a.sourceId).section }.map { (section, items) ->
                EditionSection(section, items.map { (a, c) -> toEpub(a, c, minutesOf(c), sourcesById.getValue(a.sourceId)) })
            },
            modified = clock.instant(),
            cover = coverImage,
        )
        // The try is inside withContext so a cancellation surfacing from it isn't reported as
        // a write error.
        val writeError = withContext(Dispatchers.IO) {
            try {
                editionsDir.mkdirs()
                File(editionsDir, fileName).outputStream().use { EpubWriter.write(doc, it) }
                null
            } catch (e: Exception) {
                e
            }
        }
        if (writeError != null) return fail(editionId, UNEXPECTED)

        db.withTransaction {
            db.editions().insertArticles(
                arranged.mapIndexed { i, (a, c) ->
                    EditionArticleEntity(editionId = editionId, articleId = a.id, position = i, title = c.title, sourceTitle = bylineOf(a, sourcesById.getValue(a.sourceId)), minutes = minutesOf(c), starred = a.starredAt != null)
                },
            )
            db.articles().setState(arranged.map { it.first.id }, ArticleState.IN_EDITION)
            db.editions().update(
                db.editions().byId(editionId)!!.copy(
                    status = EditionStatus.READY, fileName = fileName,
                    articleCount = arranged.size, minutes = totalMinutes,
                ),
            )
        }
        return BuildResult.Built(editionId)
    }

    /**
     * An edition still READY when the next one is built was never confirmed as
     * delivered, so its articles go back in the pool, keeping their stars.
     */
    private suspend fun releaseUndelivered() {
        val editions = db.editions()
        for (listed in editions.withStatus(EditionStatus.READY)) {
            db.withTransaction {
                // Read again inside the transaction: it may have been sent since it was listed.
                val edition = editions.byId(listed.id)?.takeIf { it.status == EditionStatus.READY } ?: return@withTransaction
                db.articles().release(editions.articleIds(edition.id))
                editions.update(edition.copy(status = EditionStatus.FAILED, error = NOT_SENT))
            }
        }
    }

    // The cover is decoration: an edition without one is still worth delivering. Drawing and
    // JPEG-encoding it is CPU work, so it goes to Default, whose threads match the cores, not IO.
    private suspend fun coverFor(info: CoverInfo): EpubImage? = withContext(Dispatchers.Default) {
        try {
            cover(info)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * A BUILDING edition at the start of a build was cut off by the process dying (a stopped
     * worker is marked FAILED on its way out). Builds share one unique work name, so it can't be
     * one still running.
     */
    private suspend fun failInterrupted() {
        for (edition in db.editions().withStatus(EditionStatus.BUILDING)) {
            db.editions().update(edition.copy(status = EditionStatus.FAILED, error = INTERRUPTED))
        }
    }

    private suspend fun fail(editionId: Long, reason: String): BuildResult {
        // A file written before the failure would otherwise sit in editions/ with nothing pointing at it.
        File(editionsDir, fileNameOf(editionId)).delete()
        db.editions().update(db.editions().byId(editionId)!!.copy(status = EditionStatus.FAILED, error = reason))
        return BuildResult.Failed(editionId, reason)
    }

    private fun toEpub(a: ArticleEntity, c: ArticleContent, minutes: Double, source: SourceEntity) = EditionArticle(
        title = c.title,
        sourceTitle = bylineOf(a, source),
        url = a.url,
        bodyHtml = c.bodyHtml,
        minutes = minutes,
        author = c.author,
        published = a.published?.atZone(zone)?.toLocalDate(),
        note = c.note,
        images = c.images,
        language = c.language,
    )

    /** The planner's source for [a]: the publication it came from within an aggregator, else its source. */
    private fun publicationOf(a: ArticleEntity) = a.originId?.let { "${a.sourceId}/$it" } ?: a.sourceId.toString()

    private fun bylineOf(a: ArticleEntity, source: SourceEntity) = a.originTitle ?: source.title

    companion object {
        private fun fileNameOf(editionId: Long) = "edition-$editionId.epub"
        private const val TAG = "EditionBuilder"
        const val NOT_SENT = "Not sent; its articles went back for the next edition."
        const val INTERRUPTED = "Interrupted; its articles will be in the next edition."
        const val STOPPED = "Stopped before it was finished; its articles will be in the next edition."
        const val UNEXPECTED = "Something went wrong making this edition. Your articles are safe and will be in the next one."
    }
}
