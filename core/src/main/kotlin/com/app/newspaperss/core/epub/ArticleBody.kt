package com.app.newspaperss.core.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

/**
 * Re-serializes an article's HTML body as XHTML that a strict XML parser accepts and that can sit
 * in a book next to other articles.
 */
internal object ArticleBody {
    private const val REMOVED_ELEMENTS =
        "script, style, noscript, iframe, frame, frameset, object, embed, applet, template, " +
            "form, input, button, select, textarea, link, meta, base, title, head, " +
            "svg, math, canvas, video, audio, source, track, map"

    // Undeclared prefixes (Word's <o:p>, xlink:href) break namespace-aware parsers, and framework
    // syntax like @click isn't a legal XML name, so only plain HTML names are kept.
    private val PLAIN_NAME = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    private val ID_UNSAFE = Regex("[^A-Za-z0-9_.-]")

    // These refer to ids by name; after prefixing they would point at nothing.
    private val ID_REFERENCE_ATTRIBUTES = setOf("for", "headers", "usemap", "aria-labelledby", "aria-describedby", "aria-controls")

    /**
     * @param idPrefix prepended to every id (and to the `#fragment` links that point at them) so
     *   ids stay unique across the book. Must start with a letter.
     * @param imageHrefs the image hrefs the book contains; any other `img` is removed.
     */
    fun toXhtml(html: String, idPrefix: String, imageHrefs: Set<String>): String {
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        body.select(REMOVED_ELEMENTS).remove()
        removeComments(body)
        for (element in body.select("*")) {
            if (element !== body && !PLAIN_NAME.matches(element.tagName())) element.unwrap()
        }
        body.select("picture").unwrap()
        cleanAttributes(body)
        val ids = prefixIds(body, idPrefix)
        fixLinks(body, ids)
        fixImages(body, imageHrefs)
        stripInvalidCharacters(body)

        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return body.html()
    }

    // "--" inside a comment is not well-formed XML, and comments have no value in a book.
    private fun removeComments(root: Element) {
        root.forEachNode { if (it is Comment) it.remove() }
    }

    private fun cleanAttributes(root: Element) {
        for (element in root.select("*")) {
            val names = element.attributes().map { it.key }
            for (name in names) {
                val drop = !(PLAIN_NAME.matches(name) || name == "xml:lang") ||
                    name.startsWith("on") || name == "style" || name == "name" || name == "xmlns" ||
                    name in ID_REFERENCE_ATTRIBUTES
                if (drop) element.removeAttr(name)
            }
        }
    }

    /** Returns the body's original ids mapped to their new names. Duplicates lose their id. */
    private fun prefixIds(root: Element, idPrefix: String): Map<String, String> {
        val ids = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for (element in root.select("[id]")) {
            val old = element.id()
            val new = idPrefix + ID_UNSAFE.replace(old, "-")
            if (old.isEmpty() || old in ids || !used.add(new)) {
                element.removeAttr("id")
            } else {
                ids[old] = new
                element.id(new)
            }
        }
        return ids
    }

    private fun fixLinks(root: Element, ids: Map<String, String>) {
        for (link in root.select("a")) {
            val href = link.attr("href").trim()
            val fixed = when {
                href.startsWith("#") -> ids[href.substring(1)]?.let { "#$it" }
                else -> externalHref(href)
            }
            if (fixed == null) {
                if (link.id().isEmpty()) link.unwrap() else link.removeAttr("href")
            } else {
                link.attr("href", fixed)
            }
        }
    }

    private fun fixImages(root: Element, imageHrefs: Set<String>) {
        for (img in root.select("img")) {
            val src = img.attr("src")
            if (src !in imageHrefs) {
                val figure = img.closest("figure")
                val link = img.parent()?.takeIf { it.tagName() == "a" }
                img.remove()
                if (link != null && link.childrenSize() == 0 && link.text().isBlank()) link.remove()
                if (figure != null && figure.select("img").isEmpty()) figure.remove()
                continue
            }
            val alt = img.attr("alt")
            val id = img.id()
            img.clearAttributes()
            img.attr("src", src).attr("alt", alt)
            if (id.isNotEmpty()) img.id(id)
        }
    }

    // Character references like &#1; decode to characters XML can't hold, so this runs after parsing.
    private fun stripInvalidCharacters(root: Element) {
        root.forEachNode { node ->
            if (node is TextNode) {
                val text = node.wholeText
                val clean = stripInvalidXmlChars(text)
                if (clean != text) node.text(clean)
            }
        }
        for (element in root.select("*")) {
            for (attribute in element.attributes()) {
                val clean = stripInvalidXmlChars(attribute.value)
                if (clean != attribute.value) attribute.setValue(clean)
            }
        }
    }
}
