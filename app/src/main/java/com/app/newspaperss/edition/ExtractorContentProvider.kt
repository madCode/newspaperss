package com.app.newspaperss.edition

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ExtractInput
import com.app.newspaperss.core.images.ArticleImages
import com.app.newspaperss.core.images.EncodedImage
import com.app.newspaperss.core.images.ImageEncoder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
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
 */
class ExtractorContentProvider(
    private val extractor: ArticleExtractor,
    private val http: HttpClient,
    private val encoder: ImageEncoder,
) : ArticleContentProvider {
    // Downloads overlap but decoding doesn't: a decoded photo can take tens of MB of heap.
    private val encoding = Mutex()

    override suspend fun contentFor(article: ArticleEntity, source: SourceEntity): ArticleContent {
        val extracted = extractor.extract(
            ExtractInput(
                url = article.url,
                feedTitle = article.title,
                feedHtml = article.feedHtml,
                feedAuthor = article.author,
                mode = source.contentMode,
            ),
        )
        val encoded = download(ArticleImages.wanted(extracted.imageUrls), referer = article.url)
        val embedded = ArticleImages.embed(extracted.html, "a${article.id}", encoded)
        return ArticleContent(
            title = extracted.title,
            author = extracted.author,
            bodyHtml = embedded.html,
            minutes = extracted.minutes,
            note = extracted.note,
            images = embedded.images,
        )
    }

    private suspend fun download(urls: List<String>, referer: String): Map<String, EncodedImage?> = coroutineScope {
        val slots = Semaphore(PARALLEL_DOWNLOADS)
        urls.map { url -> async { url to slots.withPermit { fetch(url, referer) } } }.awaitAll().toMap()
    }

    private suspend fun fetch(url: String, referer: String): EncodedImage? {
        // Image hosts often refuse hotlinking: without the article as Referer they send a 403 or a placeholder.
        val response = try {
            http.getBytes(url, mapOf("Referer" to referer, "Accept" to ACCEPT))
        } catch (e: IOException) {
            return null
        }
        if (!response.isSuccessful || response.contentType?.contains("svg", ignoreCase = true) == true) return null
        return encoding.withLock { encoder.encode(response.body) }
    }

    private companion object {
        const val PARALLEL_DOWNLOADS = 4
        const val ACCEPT = "image/jpeg,image/png,image/gif,image/webp,image/*;q=0.8"
    }
}
