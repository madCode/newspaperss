package com.app.newspaperss.edition

import com.app.newspaperss.core.notes.NotesArticle
import com.app.newspaperss.core.notes.NotesEdition
import com.app.newspaperss.core.notes.NotesWriter
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.delivery.FolderDelivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * Writes an edition's Markdown reading notes to "<edition title> notes.md" in [notesDir],
 * replacing an earlier copy: the file only lives until it's shared or saved elsewhere.
 */
class EditionNotes(
    private val db: AppDatabase,
    val notesDir: File,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** Null if the edition doesn't exist or has no articles. */
    suspend fun write(editionId: Long): File? {
        val edition = db.editions().byId(editionId) ?: return null
        val rows = db.editions().notesRows(editionId).takeIf { it.isNotEmpty() } ?: return null
        val zone = zone()
        val markdown = NotesWriter.write(
            NotesEdition(
                title = edition.title,
                date = edition.createdAt.atZone(zone).toLocalDate(),
                articles = rows.map {
                    NotesArticle(it.title, it.sourceTitle, it.url, it.author, it.published?.atZone(zone)?.toLocalDate())
                },
            ),
        )
        return withContext(Dispatchers.IO) {
            notesDir.mkdirs()
            File(notesDir, fileName(edition.title)).apply { writeText(markdown) }
        }
    }

    companion object {
        const val MIME = "text/markdown"

        fun fileName(editionTitle: String) = FolderDelivery.fileName(editionTitle, " notes.md")
    }
}
