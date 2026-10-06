package com.app.newspaperss.listen

import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.ui.edition.EpubPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * One edition to listen to: its articles in book order, then its closing page, each read out of
 * the book when it's reached.
 */
class ListenBook(
    val editionId: Long,
    val title: String,
    val pages: List<ListenPage>,
    private val read: suspend (index: Int) -> ListenScript?,
    private val readImage: suspend (src: String) -> ByteArray? = { null },
    private val onClose: () -> Unit = {},
) : Closeable {
    suspend fun script(index: Int): ListenScript? = read(index)

    /** A picture in the book, by its `src` on the page. */
    suspend fun image(src: String): ByteArray? = readImage(src)

    /** Minutes left from the start of [page], the closing page aside. */
    fun minutesFrom(page: Int): Double = pages.drop(page).sumOf { it.minutes }

    override fun close() = onClose()

    companion object {
        /** The edition's book, or null if the edition or its file is gone. */
        suspend fun open(editions: EditionRepository, editionId: Long): ListenBook? {
            val edition = editions.byId(editionId) ?: return null
            val file = editions.fileOf(edition) ?: return null
            val contents = editions.observeContents(editionId).first().sortedBy { it.entry.position }
            val book = EpubPages(file)
            val pages = contents.map { ListenPage(it.entry.title, it.entry.sourceTitle, ListenTime.fromReading(it.entry.minutes)) } +
                ListenPage(EpubWriter.END_TITLE, null, 0.0, end = true)
            return ListenBook(editionId, edition.title, pages, read = { index ->
                withContext(Dispatchers.IO) {
                    val xhtml = if (index < contents.size) book.article(contents[index].entry.position) else book.entry("OEBPS/end.xhtml")?.toString(Charsets.UTF_8)
                    xhtml?.let(ListenScript::parse)
                }
            }, readImage = { src -> withContext(Dispatchers.IO) { book.entry("OEBPS/$src") } }, onClose = book::close)
        }
    }
}

/** @param minutes about how long listening to it takes. */
data class ListenPage(val title: String, val source: String?, val minutes: Double, val end: Boolean = false)
