package com.app.newspaperss.ui.edition

import java.io.File
import java.util.zip.ZipFile

/** Reads pages and their resources back out of an edition's EPUB for the in-app preview. */
class EpubPages(private val file: File) {
    /** The XHTML of the [position]th article (0-based, reading order), or null if it isn't there. */
    fun article(position: Int): String? = entry("OEBPS/" + articleHref(position))?.toString(Charsets.UTF_8)

    /** Any file in the book by its path inside the zip, e.g. "OEBPS/style.css". */
    fun entry(path: String): ByteArray? = try {
        ZipFile(file).use { zip -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    } catch (_: java.io.IOException) {
        null
    }

    companion object {
        /** Must match EpubWriter's naming: article-001.xhtml is the first article. */
        fun articleHref(position: Int) = "article-%03d.xhtml".format(position + 1)

        fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "xhtml" -> "application/xhtml+xml"
            else -> "application/octet-stream"
        }
    }
}
