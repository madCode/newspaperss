package com.app.newspaperss.core.feed

data class ChecklistItem(val url: String, val done: Boolean, val title: String? = null)

/**
 * The reading-list format rss-to-e-reader's MarkdownCollector uses:
 * `- [ ] url` to read, `- [x] url` done. Other lines (headings, notes) are
 * ignored, and `- [ ] [Title](url)` is read too, since that's how most notes
 * apps paste a link.
 */
object MarkdownChecklist {
    private val item = Regex("""^\s*[-*]\s\[([ xX])]\s+(.+?)\s*$""")
    private val link = Regex("""^\[(.*)]\((\S+)\)$""")

    fun parse(text: String): List<ChecklistItem> = text.lineSequence().mapNotNull { line ->
        val m = item.find(line) ?: return@mapNotNull null
        val done = m.groupValues[1].isNotBlank()
        val rest = m.groupValues[2]
        val l = link.find(rest)
        val (title, url) = if (l != null) l.groupValues[1].ifBlank { null } to l.groupValues[2] else null to rest
        url.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { ChecklistItem(it, done, title) }
    }.distinctBy { it.url }.toList()

    fun write(items: List<ChecklistItem>): String = items.joinToString("") { "- [${if (it.done) "x" else " "}] ${it.url}\n" }

    /** The first web address in shared text ("Look at this https://… via @app"). */
    fun firstUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')
}
