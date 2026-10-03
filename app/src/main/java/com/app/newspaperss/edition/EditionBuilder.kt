package com.app.newspaperss.edition

import android.util.Log
import androidx.room.withTransaction
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Candidate
import com.app.newspaperss.core.edition.EditionPlanner
import com.app.newspaperss.core.edition.EditionTitles
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextCheck
import com.app.newspaperss.core.extract.HtmlCleaner
import com.app.newspaperss.core.images.ImageAllowance
import com.app.newspaperss.core.images.ImageBudget
import com.app.newspaperss.core.images.ImageRules
import com.app.newspaperss.core.net.hostOf
import com.app.newspaperss.core.notes.Reflection
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.delivery.FolderDelivery
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
        // tt-rss last, as Sources lists it: the paper's order is the list's.
        val sources = db.sources().all().filter { !it.paused }.sortedBy { it.kind == SourceKind.TTRSS }
        val sourcesById = sources.associateBy { it.id }

        // A timed edition is built ahead of its time; it's titled and dated for when it's due.
        val now = LocalDateTime.ofInstant(dueAt ?: clock.instant(), zone)
        // From the day before: an edition due just after midnight was made just before it.
        // Titles carry their date, so yesterday's can't clash.
        val since = now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant()
        val title = EditionTitles.title(now, db.editions().titlesSince(since))
        val editionId = db.editions().insert(EditionEntity(title = title, createdAt = clock.instant()))
        // Whatever goes wrong from here, the edition must not stay BUILDING: the Today screen
        // would show it as being made forever. Its articles only change state in the final
        // transaction, so a failed edition leaves them all for the next one, except paid posts
        // with nothing free that their source skips: those stay skipped.
        return try {
            // Read only once the edition is BUILDING, which holds off "Mark as read" and unstarring
            // (see ArticleDao.markReadAll): the articles picked here are written into the book, so a
            // change made afterwards would be silently undone when they're marked IN_EDITION.
            // The same link from two sources goes in once, and a starred copy is the one kept.
            // A star is the reader asking for that article, even from a feed they left out.
            val publications = db.sources().allPublications()
            val leftOut = publications.filter { it.leftOut }.map { publicationOf(it.sourceId, it.key) }.toSet()
            val articles = db.articles().candidates()
                .filter { it.sourceId in sourcesById && (it.starredAt != null || publicationOf(it) !in leftOut) }
                .sortedBy { it.starredAt == null }
                .distinctBy { it.url.ifBlank { "#${it.id}" } }
            if (articles.isEmpty()) {
                db.editions().deleteEmpty(editionId)
                BuildResult.NothingNew
            } else {
                fill(editionId, title, now, sources, publications, articles, settings, onProgress)
            }
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
        publications: List<PublicationEntity>,
        articles: List<ArticleEntity>,
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
            lastFeatured = db.editions().lastFeatured().associate { publicationOf(it.sourceId, it.originId ?: PublicationEntity.OWN) to it.createdAt },
            // Delivered ones only: an edition that's never sent gives its articles back, and
            // mustn't move its sources' turns along either.
            rotation = db.editions().countDelivered(),
        )
        var fetched = 0
        val tried = mutableListOf<Long>()
        val allowance = ImageAllowance(imageBudgetBytes)
        fun minutesOf(c: ArticleContent) = ReadingTime.minutes(c.wordCount, settings.wordsPerMinute)
        val caps = publications.mapNotNull { p -> p.maxArticles?.let { publicationOf(p.sourceId, p.key) to it } }.toMap()
        val rules = settings.rules.copy(sourceCaps = caps)
        val texts = TextChoices(publications, clock.instant().atZone(zone).toLocalDate().toEpochDay())
        val picked = EditionPlanner.fill<Pair<ArticleEntity, ArticleContent>>(ordered, rules, { minutesOf(it.second) }) { c ->
            val article = byId.getValue(c.id.toLong())
            val source = sourcesById.getValue(article.sourceId)
            tried += article.id
            val result = try {
                content.contentFor(article, source, allowance, texts.choose(article, source))?.let { article to it }
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
        if (picked.isEmpty()) {
            // Nothing new worth reading isn't a failure: every one was a paid post its source skips.
            if (db.articles().countPaidSkipped(tried) == tried.size) {
                db.editions().deleteEmpty(editionId)
                return BuildResult.NothingNew
            }
            return fail(editionId, "None of the articles could be read.")
        }

        // Which articles made it is the planner's call; reading order is the reader's own:
        // sources in list order, a tt-rss account's feeds in the order they were picked.
        val sourceIndex = sources.withIndex().associate { (i, s) -> s.id to i }
        val pickedPublications = picked.map { publicationOf(it.first) }.distinct()
        val arranged = picked.sortedWith(
            compareBy<Pair<ArticleEntity, ArticleContent>>(
                { (a, _) -> sourceIndex.getValue(a.sourceId) },
                { (a, _) -> pickedPublications.indexOf(publicationOf(a)) },
            ),
        )

        val totalMinutes = arranged.sumOf { minutesOf(it.second) }
        val coverImage = coverFor(
            CoverInfo(
                title = title,
                date = now.toLocalDate(),
                headlines = arranged.map { (a, c) -> CoverHeadline(c.title, bylineOf(a, c, sourcesById.getValue(a.sourceId))) },
                articleCount = arranged.size,
                minutes = totalMinutes,
            ),
        )

        // Budgeted again in reading order (fetch order differs), so the first articles keep their
        // pictures. EpubWriter drops an img whose image isn't in the book, and an emptied figure.
        val articleImageBudget = (imageBudgetBytes - (coverImage?.bytes?.size ?: 0)).coerceAtLeast(0)
        val fitted = ImageBudget.fit(arranged.map { it.second.images }, articleImageBudget)
        val withImages = arranged.zip(fitted) { (a, c), images -> a to c.copy(images = images) }

        val fileName = fileNameOf(title)
        val doc = EditionDoc(
            title = title,
            date = now.toLocalDate(),
            identifier = "urn:uuid:${UUID.randomUUID()}",
            sections = listOf(EditionSection(null, withImages.map { (a, c) -> toEpub(a, c, minutesOf(c), sourcesById.getValue(a.sourceId)) })),
            modified = clock.instant(),
            cover = coverImage,
            reflection = Reflection.forEdition(editionId),
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
                    EditionArticleEntity(editionId = editionId, articleId = a.id, position = i, title = c.title, sourceTitle = bylineOf(a, c, sourcesById.getValue(a.sourceId)), minutes = minutesOf(c), starred = a.starredAt != null, stateBefore = a.state)
                },
            )
            db.articles().setState(arranged.map { it.first.id }, ArticleState.IN_EDITION)
            db.articles().unlinkFromStory(arranged.filter { it.second.notTheStory }.map { it.first.id })
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
                db.articles().release(edition.id)
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
        val edition = db.editions().byId(editionId)!!
        File(editionsDir, fileNameOf(edition.title)).delete()
        db.editions().update(edition.copy(status = EditionStatus.FAILED, error = reason))
        return BuildResult.Failed(editionId, reason)
    }

    private fun toEpub(a: ArticleEntity, c: ArticleContent, minutes: Double, source: SourceEntity) = EditionArticle(
        title = c.title,
        sourceTitle = bylineOf(a, c, source),
        url = a.viaUrl?.takeIf { c.notTheStory } ?: a.url,
        bodyHtml = c.bodyHtml,
        minutes = minutes,
        author = c.author,
        published = a.published?.atZone(zone)?.toLocalDate(),
        note = c.note,
        images = c.images,
        language = c.language,
    )

    /** The planner's source for [a]: the publication it came from. */
    private fun publicationOf(a: ArticleEntity) = publicationOf(a.sourceId, PublicationEntity.keyOf(a))

    private fun publicationOf(sourceId: Long, key: String) = if (key == PublicationEntity.OWN) sourceId.toString() else "$sourceId/$key"

    /** Where [a] came from: its source, or for a link post "Equator via Longreads". */
    private fun bylineOf(a: ArticleEntity, c: ArticleContent, source: SourceEntity): String {
        val from = a.originTitle ?: source.title
        return if (a.viaUrl == null || c.notTheStory) from else "${c.siteName ?: hostOf(a.url)} via $from"
    }

    companion object {
        /**
         * The edition's title: Send to Kindle takes the shared file's name as the book's title, and
         * the Kindle library would list "edition-3". Titles are unique among recent editions,
         * and a dated title ("Wednesday … Sep 30") only comes round again years later.
         */
        private fun fileNameOf(title: String) = FolderDelivery.fileName(title)
        private const val TAG = "EditionBuilder"
        // From the app's side: a reader who sent it some other way and didn't say so gets this too.
        const val NOT_SENT = "This one wasn't marked as sent, so its articles went back for your next edition."
        /** NOT_SENT as editions released before its wording changed still have it. */
        const val OLD_NOT_SENT = "Not sent; its articles went back for the next edition."
        const val INTERRUPTED = "Interrupted; its articles will be in the next edition."
        const val STOPPED = "Stopped before it was finished; its articles will be in the next edition."
        const val UNEXPECTED = "Something went wrong making this edition. Your articles are safe and will be in the next one."
    }
}

/**
 * Each article's [TextChoice]: what its publication has learned, and whether it's one of the
 * edition's [FullTextCheck.CHECKS_PER_EDITION] checks. Articles are fetched in plan order, so the
 * checks go to the first long items from publications that are due, one per publication.
 */
private class TextChoices(publications: List<PublicationEntity>, private val today: Long) {
    private val byKey = publications.associateBy { it.sourceId to it.key }
    private val checked = mutableSetOf<Pair<Long, String>>()

    fun choose(article: ArticleEntity, source: SourceEntity): TextChoice {
        if (source.kind != SourceKind.FEED && source.kind != SourceKind.TTRSS) return TextChoice()
        val key = source.id to PublicationEntity.keyOf(article)
        val publication = byKey[key]
        publication?.chosenMode?.let { return TextChoice(chosen = it) }
        val mode = publication?.contentMode ?: ContentMode.AUTO
        val check = checked.size < FullTextCheck.CHECKS_PER_EDITION && key !in checked && article.viaUrl == null &&
            FullTextCheck.dueForCheck(mode, publication?.fullTextEvidence, publication?.checkedDay, today) && isLong(article)
        if (check) checked += key
        return TextChoice(publication?.contentMode, check, today)
    }

    // A short item has its page fetched anyway; only a long one needs a check to find a teaser.
    // Counted as the extractor counts, after cleaning, so the two agree on what's long.
    private fun isLong(article: ArticleEntity) =
        (article.feedHtml?.let { HtmlCleaner.clean(it, article.url, article.title).wordCount } ?: 0) >= ArticleExtractor.FULL_TEXT_WORDS
}
