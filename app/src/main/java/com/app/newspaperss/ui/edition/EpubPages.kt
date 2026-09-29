package com.app.newspaperss.ui.edition

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads pages and their resources back out of an edition's EPUB for the in-app preview.
 *
 * The zip is opened on first read and kept open until [close]: a page asks for its stylesheet
 * and every image, and reopening the zip for each is a central-directory read apiece. Reads are
 * safe from several threads (the WebView serves requests off the main thread), and a read after
 * [close] returns null rather than throwing, since the WebView can still be asking as its
 * screen goes away.
 */
class EpubPages(private val file: File) : Closeable {
    private var zip: ZipFile? = null
    private var closed = false

    /** The XHTML of the [position]th article (0-based, reading order), or null if it isn't there. */
    fun article(position: Int): String? = entry("OEBPS/" + articleHref(position))?.toString(Charsets.UTF_8)

    /** Any file in the book by its path inside the zip, e.g. "OEBPS/style.css". */
    fun entry(path: String): ByteArray? = try {
        open()?.let { zip -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        // Closed between open() and the read.
        null
    }

    @Synchronized
    private fun open(): ZipFile? {
        if (closed) return null
        return zip ?: ZipFile(file).also { zip = it }
    }

    @Synchronized
    override fun close() {
        closed = true
        zip?.close()
        zip = null
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
