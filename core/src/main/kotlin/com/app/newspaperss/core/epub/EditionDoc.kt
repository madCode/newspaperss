package com.app.newspaperss.core.epub

import java.time.Instant
import java.time.LocalDate

/**
 * Everything [EpubWriter] needs to write one edition.
 *
 * @property title the edition's title, also the book title the e-reader shows. Send to Kindle
 *   drops a document whose title it has seen before, so callers make it unique per day.
 * @property date the edition's date, shown on the cover and written as `dc:date`.
 * @property language BCP 47 language code of the content.
 * @property identifier a unique id for this book, usually `urn:uuid:…`.
 * @property sections the edition's articles in reading order, grouped into sections.
 * @property masthead the paper's name, shown on the cover and written as the book's creator.
 * @property modified written as `dcterms:modified` and used as the zip entries' timestamps.
 * @property cover the cover image e-readers show as the library thumbnail, also shown on the cover
 *   page. Its href follows the same rules as article images and must not be one of theirs. Without
 *   it the cover page is text only.
 */
data class EditionDoc(
    val title: String,
    val date: LocalDate,
    val identifier: String,
    val sections: List<EditionSection>,
    val language: String = "en",
    val masthead: String = "newspapeRSS",
    val modified: Instant = Instant.now(),
    val cover: EpubImage? = null,
) {
    val articles: List<EditionArticle> get() = sections.flatMap { it.articles }
}

/**
 * A group of articles. A section with a null or blank [title] is written without a heading and
 * its articles appear at the top level of the table of contents.
 */
data class EditionSection(
    val title: String?,
    val articles: List<EditionArticle>,
)

/**
 * One article of an edition.
 *
 * @property title the headline. A blank title is shown as "Article N".
 * @property sourceTitle the name of the source it came from.
 * @property author the byline's author, if known.
 * @property published the date shown in the byline, if known.
 * @property url the original page, linked at the end of the article.
 * @property bodyHtml the cleaned article body as an HTML fragment. It is re-serialized as strict
 *   XHTML; `img` elements whose `src` is not one of [images] are dropped, because an e-book can't
 *   load images from the internet.
 * @property minutes reading time, fractional.
 * @property note a notice shown above the body, for example that the full article couldn't be
 *   fetched.
 * @property images the images [bodyHtml] references, by the same href.
 * @property language the article's BCP 47 language tag, if known. Its headline and body are
 *   tagged with it where it differs from the book's [EditionDoc.language].
 */
data class EditionArticle(
    val title: String,
    val sourceTitle: String,
    val url: String,
    val bodyHtml: String,
    val minutes: Double,
    val author: String? = null,
    val published: LocalDate? = null,
    val note: String? = null,
    val images: List<EpubImage> = emptyList(),
    val language: String? = null,
)

/**
 * An image stored in the book.
 *
 * @property href the path inside the book, which must start with `images/` (for example
 *   `images/a1-0.jpg`), and the exact `src` the article body uses for it.
 * @property mediaType `image/jpeg`, `image/png` or `image/gif`; e-readers handle these reliably.
 */
class EpubImage(
    val href: String,
    val mediaType: String,
    val bytes: ByteArray,
)
