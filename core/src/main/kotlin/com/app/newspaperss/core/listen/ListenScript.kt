package com.app.newspaperss.core.listen

import com.app.newspaperss.core.ReadingTime
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * A page of the edition, ready to read aloud: what the screen shows while listening, in blocks,
 * and what the voice says, one [Line] at a time. A line is a sentence, or an image's one-line
 * description, so going back a line is going back a sentence.
 */
data class ListenScript(val blocks: List<Block>, val language: String? = null) {
    /** Everything spoken, in order. */
    val lines: List<Line> = blocks.flatMapIndexed { b, block ->
        when (block) {
            is Block.Text -> block.sentences.mapIndexed { s, sentence -> Line(b, s, sentence) }
            is Block.Image -> listOf(Line(b, 0, block.spoken))
        }
    }

    /** About how long reading it aloud takes, at normal speed. */
    val seconds: Double get() = lines.sumOf { ListenTime.seconds(it.spoken) }

    sealed interface Block {
        data class Text(val kind: Kind, val sentences: List<String>) : Block

        /** @param description its caption or alt text, or null if the article gives neither. */
        data class Image(val src: String, val description: String?) : Block {
            val spoken: String get() = description?.let { "Image: $it" } ?: "An image."
        }
    }

    enum class Kind { KICKER, TITLE, BYLINE, HEADING, PARAGRAPH, QUOTE, ITEM }

    /** One thing the voice says: the [index]th sentence of the [block]th block. */
    data class Line(val block: Int, val index: Int, val spoken: String)

    companion object {
        /**
         * Reads a page of the book EpubWriter made: an article (kicker, title, the author from
         * its byline, then the body; not the link to the original or the "Next" line), or the
         * closing page. The body is anything an article's HTML can be, so it's walked generally:
         * text that sits together is a paragraph, and every picture is a block of its own.
         */
        fun parse(xhtml: String): ListenScript {
            val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
            val html = doc.selectFirst("html")
            val bodyElement = doc.selectFirst("div.article-body")
            val language = (bodyElement?.attr("xml:lang")?.ifEmpty { null } ?: bodyElement?.attr("lang")?.ifEmpty { null })
                ?: html?.attr("xml:lang")?.ifEmpty { null } ?: html?.attr("lang")?.ifEmpty { null }
            val out = Builder()
            val page = doc.selectFirst("body") ?: return ListenScript(emptyList(), language)
            for (element in page.children()) {
                when {
                    element.hasClass("kicker") -> out.text(Kind.KICKER, element.text())
                    element.hasClass("article-title") || element.hasClass("end-title") -> out.text(Kind.TITLE, element.text())
                    // The date and reading time are for the page; spoken, they'd be noise.
                    element.hasClass("byline") -> element.text().split(" · ").firstOrNull { it.startsWith("By ") }?.let { out.text(Kind.BYLINE, it) }
                    element.hasClass("article-body") -> out.body(element, Kind.PARAGRAPH)
                    element.hasClass("reflection") -> out.body(element, Kind.PARAGRAPH)
                    element.hasClass("note") || element.hasClass("end-summary") -> out.text(Kind.PARAGRAPH, element.text())
                    // Left out: the rule, the end mark, the original's address, the "Next" line
                    // and the end page's imprint.
                }
            }
            return ListenScript(out.blocks, language)
        }

        private class Builder {
            val blocks = mutableListOf<Block>()
            private val pending = StringBuilder()

            fun text(kind: Kind, text: String) {
                val sentences = Sentences.split(text)
                if (sentences.isNotEmpty()) blocks += Block.Text(kind, sentences)
            }

            fun body(container: Element, kind: Kind) {
                for (node in container.childNodes()) visit(node, kind)
                flush(kind)
            }

            private fun flush(kind: Kind) {
                text(kind, pending.toString())
                pending.clear()
            }

            private fun visit(node: Node, kind: Kind) {
                when (node) {
                    is TextNode -> pending.append(node.wholeText)
                    is Element -> when (val tag = node.normalName()) {
                        in SKIPPED -> {}
                        // A footnote's marker or its link back, which would be read as a stray
                        // number or arrow: a short link within the page.
                        "a" -> if (!(node.attr("href").startsWith("#") || node.attr("href").contains(".xhtml#")) || node.text().trim().length > FOOTNOTE_MARKER_MAX) {
                            for (child in node.childNodes()) visit(child, kind)
                        }
                        "br" -> pending.append(' ')
                        "img" -> {
                            flush(kind)
                            image(node, null)
                        }
                        "figure" -> {
                            flush(kind)
                            figure(node, kind)
                        }
                        "table" -> {
                            flush(kind)
                            // A row at a time, its cells run together: a table read cell by
                            // cell, with a pause after each, is hard to follow.
                            for (row in node.select("tr")) text(kind, row.select("th, td").joinToString(", ") { it.text() })
                        }
                        in BLOCKS -> {
                            flush(kind)
                            val inner = when (tag) {
                                "h1", "h2", "h3", "h4", "h5", "h6" -> Kind.HEADING
                                "blockquote" -> Kind.QUOTE
                                "li", "dd", "dt" -> if (kind == Kind.QUOTE) kind else Kind.ITEM
                                else -> kind
                            }
                            for (child in node.childNodes()) visit(child, inner)
                            flush(inner)
                        }
                        else -> for (child in node.childNodes()) visit(child, kind)
                    }
                    else -> {}
                }
            }

            private fun figure(figure: Element, kind: Kind) {
                val caption = figure.selectFirst("figcaption")?.text()?.trim()?.ifEmpty { null }
                val images = figure.select("img")
                images.forEach { image(it, caption.takeIf { images.size == 1 }) }
                if (images.size != 1 && caption != null) text(kind, caption)
                // Anything else in the figure (a quote, a table) is read as text.
                for (child in figure.childNodes()) {
                    if (child is Element && (child.normalName() == "figcaption" || child.normalName() == "img" || child.select("img").isNotEmpty())) continue
                    visit(child, kind)
                }
                flush(kind)
            }

            private fun image(img: Element, caption: String?) {
                val description = caption ?: img.attr("alt").trim().ifEmpty { null }
                blocks += Block.Image(img.attr("src"), description)
            }
        }

        private val SKIPPED = setOf("script", "style", "noscript", "svg", "math", "aside", "nav")
        private const val FOOTNOTE_MARKER_MAX = 4
        private val BLOCKS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "address", "details", "summary", "hr", "figcaption",
        )
    }
}

/** How long reading aloud takes: a voice reads about 160 words a minute, against 238 read silently. */
object ListenTime {
    const val WPM = 160

    /** Seconds to say [text], counting the pause after it. */
    fun seconds(text: String): Double = ReadingTime.words(text) * 60.0 / WPM + PAUSE

    /** Listening minutes for something that takes [readingMinutes] to read. */
    fun fromReading(readingMinutes: Double): Double = readingMinutes * ReadingTime.DEFAULT_WPM / WPM

    private const val PAUSE = 0.4
}
