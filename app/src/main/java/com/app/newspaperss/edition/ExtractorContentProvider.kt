package com.app.newspaperss.edition

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.ExtractInput
import com.app.newspaperss.core.extract.FullTextCheck
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.images.ArticleImages
import com.app.newspaperss.core.images.EncodedImage
import com.app.newspaperss.core.images.ImageAllowance
import com.app.newspaperss.core.images.ImageEncoder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/**
 * Extracts each article and embeds its images. The edition-wide image size budget is applied
 * later by [EditionBuilder], in reading order.
 *
 * @param onPaidOnly told of each paid post with next to nothing free, and whether it was left out
 *   for its source's [SourceEntity.skipPaidPosts], for [com.app.newspaperss.data.SourceRepository.markPaidOnly].
 * @param onEvidence receives what each article showed about where its source's full text is,
 *   for [com.app.newspaperss.data.SourceRepository.recordFullText].
 */
class ExtractorContentProvider(
    private val extractor: ArticleExtractor,
    private val http: HttpClient,
    private val encoder: ImageEncoder,
    private val onPaidOnly: suspend (articleId: Long, skipped: Boolean) -> Unit = { _, _ -> },
    private val onEvidence: suspend (sourceId: Long, FullTextEvidence) -> Unit,
) : ArticleContentProvider {
    // Downloads overlap but decoding doesn't: a decoded photo can take tens of MB of heap.
    private val encoding = Mutex()

    override suspend fun contentFor(article: ArticleEntity, source: SourceEntity, images: ImageAllowance): ArticleContent? {
        val extracted = extractor.extract(
            ExtractInput(
                url = article.url,
                feedTitle = article.title,
                feedHtml = article.feedHtml,
                feedAuthor = article.author,
                mode = modeFor(article, source),
                feedUrl = article.viaUrl,
            ),
        )
        // A link post's story page against its pitch says nothing about the source's own feed.
        if (article.viaUrl == null) FullTextCheck.evidence(extracted)?.let { onEvidence(source.id, it) }
        // A feed article that can't be read still goes in, so a broken feed gets noticed. A link the
        // reader saved on purpose waits for the next edition instead of being used up as a stub.
        if (source.kind == SourceKind.READING_LIST && extracted.wordCount == 0) return null
        if (extracted.nothingFree) {
            // A star asks for this article whatever it turns out to be.
            val skip = source.skipPaidPosts && article.starredAt == null
            onPaidOnly(article.id, skip)
            if (skip) return null
        }
        val encoded = download(ArticleImages.wanted(extracted.imageUrls), refererFor(article.url), images)
        val embedded = ArticleImages.embed(extracted.html, "a${article.id}", encoded)
        return ArticleContent(
            title = extracted.title,
            author = extracted.author,
            bodyHtml = embedded.html,
            wordCount = extracted.wordCount,
            note = extracted.note,
            images = embedded.images,
            language = extracted.language,
            siteName = extracted.siteName,
            notTheStory = extracted.notTheStory,
        )
    }

    private suspend fun download(urls: List<String>, referer: String, allowance: ImageAllowance): Map<String, EncodedImage?> = coroutineScope {
        val slots = Semaphore(PARALLEL_DOWNLOADS)
        urls.map { url ->
            async { url to slots.withPermit { if (allowance.exhausted) null else fetch(url, referer)?.takeIf { allowance.take(it.bytes.size.toLong()) } } }
        }.awaitAll().toMap()
    }

    private suspend fun fetch(url: String, referer: String): EncodedImage? {
        // Image hosts often refuse hotlinking: without a Referer from the article's site they send a 403 or a placeholder.
        val response = try {
            http.getBytes(url, mapOf("Referer" to referer, "Accept" to ACCEPT))
        } catch (e: IOException) {
            return null
        }
        if (!response.isSuccessful || response.contentType?.contains("svg", ignoreCase = true) == true) return null
        return encoding.withLock { encoder.encode(response.body) }
    }

    private companion object {
        /**
         * A source the check settled on the feed's text still has its short items, and ones ending
         * in "Read more", checked against the page: otherwise it could never find out that the site
         * stopped blocking or started sending teasers, or tell a paid post's opening. AUTO does
         * that and takes a long feed text as it is. A mode the reader chose is used as is. A link
         * post's story is always fetched unless the reader chose the feed's text: its pitch is
         * never the article.
         */
        fun modeFor(article: ArticleEntity, source: SourceEntity): ContentMode {
            if (article.viaUrl != null) {
                return if (source.contentModeChosen && source.contentMode == ContentMode.FEED) ContentMode.FEED else ContentMode.PAGE
            }
            return if (source.contentModeChosen || source.contentMode != ContentMode.FEED) source.contentMode else ContentMode.AUTO
        }

        /**
         * Only the article's origin, as browsers send across sites: the full URL can carry
         * tokens and tracking parameters, and hotlink checks only look at the host.
         */
        fun refererFor(url: String): String =
            runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault("")

        const val PARALLEL_DOWNLOADS = 4
        const val ACCEPT = "image/jpeg,image/png,image/gif,image/webp,image/*;q=0.8"
    }
}
