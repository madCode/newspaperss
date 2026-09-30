package com.app.newspaperss.edition

import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.FolderWriter
import com.app.newspaperss.notify.EditionNotifier
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CancellationException

/** Saves a delivered edition's reading notes to the notes folder, if the reader chose one. */
class NotesSaver(
    private val settings: SettingsStore,
    private val editions: EditionRepository,
    private val notes: EditionNotes,
    private val folder: FolderWriter,
    private val notifier: EditionNotifier,
) {
    suspend fun save(editionId: Long) {
        val folderUri = settings.current().notesFolderUri ?: return
        val edition = editions.byId(editionId)?.takeIf { it.status == EditionStatus.DELIVERED } ?: return
        val error = try {
            notes.write(editionId)?.let { folder.deliver(it, folderUri, it.name, EditionNotes.MIME) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Couldn't write the notes (${e.message ?: e.javaClass.simpleName})."
        }
        error?.let { notifier.problem("Notes for ${edition.title} weren't saved", it) }
    }
}
