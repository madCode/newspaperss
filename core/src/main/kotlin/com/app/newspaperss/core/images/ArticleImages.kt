package com.app.newspaperss.core.images

import com.app.newspaperss.core.epub.EpubImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities

/**
 * An article body whose images point into the book.
 *
 * @property html the body with every `img` either pointing at one of [images] or removed.
 * @property images the images [html] references, in document order.
 */
class EmbeddedImages(val html: String, val images: List<EpubImage>)

/** Chooses which of an article's images to download and points its HTML at the downloaded copies. */
object ArticleImages {
    private val ARTICLE_KEY = Regex("[A-Za-z0-9_-]+")
    private val EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif")

    /**
     * The images worth downloading: the first [ImageRules.MAX_PER_ARTICLE] that aren't SVG, which
     * e-readers render badly or not at all.
     */
    fun wanted(imageUrls: List<String>): List<String> =
        imageUrls.distinct().filterNot(::isSvg).take(ImageRules.MAX_PER_ARTICLE)

    fun isSvg(url: String) = url.substringBefore('#').substringBefore('?').lowercase().endsWith(".svg")

    /**
     * Points each `img` in [html] at its copy in the book, named `images/<articleKey>-<n>.<ext>`
     * in document order. An `img` whose URL is missing from [encoded] or maps to null is removed,
     * and so is a `figure` left without any image, so no caption is left captioning nothing.
     *
     * @param html a cleaned article body whose `img` sources are the URLs in [encoded].
     * @param articleKey unique within the edition, so two articles' images never share an href.
     * @param encoded image URL to its encoded copy, or null if it couldn't be used.
     */
    fun embed(html: String, articleKey: String, encoded: Map<String, EncodedImage?>): EmbeddedImages {
        require(ARTICLE_KEY.matches(articleKey)) { "Article key must be letters, digits, '-' or '_': $articleKey" }
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        val byUrl = LinkedHashMap<String, EpubImage>()
        for (img in body.select("img")) {
            val src = img.attr("src")
            val image = byUrl[src] ?: encoded[src]?.let { e ->
                EXTENSIONS[e.mediaType]?.let { ext -> EpubImage("images/$articleKey-${byUrl.size + 1}.$ext", e.mediaType, e.bytes) }
            }?.also { byUrl[src] = it }
            if (image != null) {
                img.attr("src", image.href)
                continue
            }
            val figure = img.closest("figure")
            img.remove()
            if (figure != null && figure.selectFirst("img") == null) figure.remove()
        }
        doc.outputSettings()
            .prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
        return EmbeddedImages(body.html(), byUrl.values.toList())
    }
}

object ImageBudget {
    /**
     * Keeps images, in order, while their total size fits in [maxBytes]. An image that doesn't fit
     * is dropped but a smaller one after it may still get in, so one huge photo doesn't cost the
     * rest of the edition its pictures.
     *
     * @param images each article's images, in reading order.
     * @return the images kept, per article, in the same shape as [images].
     */
    fun fit(images: List<List<EpubImage>>, maxBytes: Long = ImageRules.MAX_EDITION_BYTES): List<List<EpubImage>> {
        var total = 0L
        return images.map { article ->
            article.filter { image ->
                (total + image.bytes.size <= maxBytes).also { fits -> if (fits) total += image.bytes.size }
            }
        }
    }
}
