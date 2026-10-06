package com.app.newspaperss.core.feed

import com.app.newspaperss.core.epub.esc
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** One subscription from an OPML file; [folder] is its enclosing outline's title, if any. */
data class OpmlFeed(val url: String, val title: String?, val folder: String?)

object Opml {
    fun parse(xml: String): List<OpmlFeed> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("outline[xmlUrl]").map { el ->
            val parent = el.parent()
            val folder = if (parent != null && parent.tagName() == "outline" && !parent.hasAttr("xmlUrl")) {
                parent.attr("title").ifBlank { parent.attr("text") }.ifBlank { null }
            } else null
            OpmlFeed(
                url = el.attr("xmlUrl").trim(),
                title = el.attr("title").ifBlank { el.attr("text") }.ifBlank { null },
                folder = folder,
            )
        }.filter { it.url.isNotEmpty() }.distinctBy { it.url }
    }

    fun write(title: String, feeds: List<OpmlFeed>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n")
        append("  <head><title>").append(esc(title)).append("</title></head>\n  <body>\n")
        fun outline(f: OpmlFeed, indent: String) {
            val t = esc(f.title ?: f.url)
            append(indent).append("<outline type=\"rss\" text=\"").append(t).append("\" title=\"").append(t)
                .append("\" xmlUrl=\"").append(esc(f.url)).append("\"/>\n")
        }
        feeds.filter { it.folder == null }.forEach { outline(it, "    ") }
        feeds.filter { it.folder != null }.groupBy { it.folder!! }.forEach { (folder, list) ->
            append("    <outline text=\"").append(esc(folder)).append("\" title=\"").append(esc(folder)).append("\">\n")
            list.forEach { outline(it, "      ") }
            append("    </outline>\n")
        }
        append("  </body>\n</opml>\n")
    }
}
