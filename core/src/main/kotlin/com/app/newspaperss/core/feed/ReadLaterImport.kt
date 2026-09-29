package com.app.newspaperss.core.feed

import org.jsoup.Jsoup

/** A reading list read from a file, and which app's format it was in. */
data class ReadingListFile(val format: ReadingListFile.Format, val items: List<ChecklistItem>) {
    enum class Format { MARKDOWN, POCKET_HTML, POCKET_CSV, INSTAPAPER_CSV }
}

/**
 * Reads the exports people bring from read-later apps, telling the format
 * apart by content because files arrive from the picker with unreliable
 * names and MIME types. Archived or read items come back as done.
 */
object ReadLaterImport {
    fun parse(text: String): ReadingListFile {
        val t = text.removePrefix(BOM)
        val header = csvHeader(t)
        return when {
            header != null && "time_added" in header -> ReadingListFile(ReadingListFile.Format.POCKET_CSV, pocketCsv(t))
            header != null && "folder" in header -> ReadingListFile(ReadingListFile.Format.INSTAPAPER_CSV, instapaperCsv(t))
            looksLikeHtml(t) -> ReadingListFile(ReadingListFile.Format.POCKET_HTML, pocketHtml(t))
            else -> ReadingListFile(ReadingListFile.Format.MARKDOWN, MarkdownChecklist.parse(t))
        }
    }

    /**
     * Pocket's HTML export: one `<ul>` of links under an "Unread" heading and
     * one under "Read Archive".
     */
    fun pocketHtml(html: String): List<ChecklistItem> {
        var archived = false
        val items = mutableListOf<ChecklistItem>()
        for (el in Jsoup.parse(html).select("h1, h2, a[href]")) {
            if (el.tagName() != "a") {
                archived = el.text().contains("archive", ignoreCase = true)
                continue
            }
            item(el.attr("href"), el.text(), archived)?.let { items += it }
        }
        return items.distinctBy { it.url }
    }

    /** Pocket's CSV export: title,url,time_added,tags,status, where status is "unread" or "archive". */
    fun pocketCsv(text: String): List<ChecklistItem> = fromCsv(text, doneColumn = "status", doneValue = "archive")

    /** Instapaper's CSV export: URL,Title,Selection,Folder,Timestamp, where the "Archive" folder holds what was read. */
    fun instapaperCsv(text: String): List<ChecklistItem> = fromCsv(text, doneColumn = "folder", doneValue = "archive")

    // Columns are found by name, so a reordered or extended export still reads.
    private fun fromCsv(text: String, doneColumn: String, doneValue: String): List<ChecklistItem> {
        val rows = csv(text.removePrefix(BOM))
        val names = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        val url = names.indexOf("url").takeIf { it >= 0 } ?: return emptyList()
        val title = names.indexOf("title")
        val done = names.indexOf(doneColumn)
        return rows.drop(1).mapNotNull { fields ->
            fun field(i: Int) = fields.getOrNull(i)?.trim().orEmpty()
            item(field(url), field(title), field(done).equals(doneValue, ignoreCase = true))
        }.distinctBy { it.url }
    }

    // Both apps fill in the address as the title when a page had none.
    private fun item(url: String, title: String, done: Boolean): ChecklistItem? {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val t = title.replace(WHITESPACE, " ").trim().takeUnless { it.isEmpty() || it == u }
        return ChecklistItem(u, done, t)
    }

    private fun csvHeader(text: String): Set<String>? {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val names = csv(firstLine).firstOrNull()?.map { it.trim().lowercase() }?.toSet() ?: return null
        return names.takeIf { "url" in it && "title" in it }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(1024).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || (head.startsWith("<") && "<a " in text.lowercase())
    }

    /**
     * RFC 4180 CSV: fields may be quoted, and a quoted field can hold commas,
     * newlines and doubled quotes (`""`). Blank lines are skipped.
     */
    internal fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row += field.toString(); field.clear() }
        fun endRow() {
            endField()
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> endField()
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { endRow(); i++ }
                c == '\n' || c == '\r' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private val WHITESPACE = Regex("\\s+")
    private const val BOM = "\uFEFF"
}
