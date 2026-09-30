package com.app.newspaperss.core.notes

import java.time.LocalDate

data class NotesEdition(
    val title: String,
    val date: LocalDate,
    val articles: List<NotesArticle>,
)

data class NotesArticle(
    val title: String,
    val sourceTitle: String,
    /** Null when the article is no longer known, e.g. its source was removed. */
    val url: String?,
    val author: String? = null,
    val published: LocalDate? = null,
)

/**
 * A Markdown notes file for an edition, for Obsidian, Logseq or any Markdown editor: YAML front
 * matter (so Obsidian's properties, tags and Dataview see the date and sources), a few reflection
 * prompts once at the top, then per article its details, a citation and an empty place for notes.
 */
object NotesWriter {
    // Once for the edition rather than under every article: forty prompts read as homework.
    val PROMPTS = listOf(
        "What's the main claim, and do I agree?",
        "How does it connect to other things I've read?",
        "What do I want to remember in six months?",
    )

    fun write(edition: NotesEdition): String = buildString {
        val editionTitle = text(edition.title)
        append("---\n")
        append("date: ").append(edition.date).append('\n')
        append("edition: ").append(yaml(edition.title)).append('\n')
        val sources = edition.articles.map { it.sourceTitle.replace(whitespace, " ").replace(controls, "").trim() }.filter { it.isNotEmpty() }.distinct()
        if (sources.isNotEmpty()) {
            append("sources:\n")
            sources.forEach { append("  - ").append(yaml(it)).append('\n') }
        }
        append("tags:\n  - newspaperss\n")
        append("---\n\n")
        append("# ").append(editionTitle).append("\n\n")
        append("Pick one piece that stayed with you. Some questions to start:\n\n")
        PROMPTS.forEach { append("- ").append(it).append('\n') }
        for (article in edition.articles) {
            val title = text(article.title)
            val source = text(article.sourceTitle)
            val author = article.author?.takeIf { it.isNotBlank() }?.let(::text)
            append("\n## ").append(title).append("\n\n")
            append("- Source: ").append(source).append('\n')
            author?.let { append("- Author: ").append(it).append('\n') }
            article.published?.let { append("- Published: ").append(it).append('\n') }
            article.url?.let { append("- Link: ").append(it).append('\n') }
            append("- Read in: ").append(editionTitle).append(", ").append(edition.date).append('\n')
            append("- Citation: ").append(citation(title, source, author, article)).append('\n')
            append("\n### Notes\n")
        }
    }

    /**
     * A double-quoted YAML string. Control characters go: titles can carry a stray one (a decoded
     * `&#7;`), and strict parsers reject the whole front matter over it.
     */
    private fun yaml(value: String): String =
        "\"" + value.replace(whitespace, " ").replace(controls, "").trim()
            .replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val controls = Regex("""[\u0000-\u001F\u007F-\u009F]""")

    /** A simple MLA-like line: Author. "Title." *Source*, date. link */
    private fun citation(title: String, source: String, author: String?, article: NotesArticle): String =
        listOfNotNull(
            author?.let(::sentence),
            "\"${sentence(title)}\"",
            sentence("*$source*" + (article.published?.let { ", $it" } ?: "")),
            article.url,
        ).joinToString(" ")

    private fun sentence(s: String) = if (s.last() in ".?!") s else "$s."

    private val whitespace = Regex("""\s+""")

    // Backslash-escaping any ASCII punctuation is valid CommonMark, so over-escaping only costs
    // readability of the raw file. `$` is here because Obsidian renders "$5 to $10" as math.
    private const val SIGNIFICANT = "\\`*_[]#<>|~$&"

    /** One line of plain text: feed titles can hold newlines and Markdown syntax. */
    private fun text(value: String): String = buildString {
        for (c in value.replace(whitespace, " ").trim()) {
            if (c in SIGNIFICANT) append('\\')
            append(c)
        }
    }.ifEmpty { "Untitled" }
}
