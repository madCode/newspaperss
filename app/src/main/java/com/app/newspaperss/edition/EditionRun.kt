package com.app.newspaperss.edition

import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.delivery.FolderWriter
import com.app.newspaperss.notify.EditionNotifier
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import java.io.IOException

/** One edition from start to finish: sync, build, deliver, tell the reader. */
class EditionRun(
    private val settings: SettingsStore,
    private val sync: FeedSync,
    private val builder: EditionBuilder,
    private val editions: EditionRepository,
    private val folder: FolderWriter,
    private val notifier: EditionNotifier,
    private val notes: EditionNotes,
) {
    /**
     * @param scheduled true for the timed run. Only then does a shared edition
     *   get a "ready" notification; someone who tapped "Make one now" is
     *   already looking at it.
     */
    suspend fun run(scheduled: Boolean, onSyncDone: () -> Unit = {}, onProgress: (Int) -> Unit = {}): BuildResult {
        val s = settings.current()
        sync.syncAll()
        onSyncDone()
        val result = builder.build(s.edition, onProgress)
        when (result) {
            is BuildResult.Built -> deliver(result.editionId, s, scheduled)
            is BuildResult.Failed -> if (scheduled) notifier.problem("Today's edition couldn't be made", result.reason)
            BuildResult.NothingNew -> {}
        }
        return result
    }

    private suspend fun deliver(editionId: Long, s: Settings, scheduled: Boolean) {
        val edition = editions.byId(editionId) ?: return
        val file = editions.fileOf(edition) ?: return
        val folderUri = s.folderUri
        if (s.delivery == DeliveryMethod.FOLDER && folderUri != null) {
            val error = folder.deliver(file, folderUri, FolderDelivery.fileName(edition.title), EditionIntents.EPUB_MIME)
            if (error == null) {
                editions.markDelivered(editionId)
                notifier.editionDelivered(edition, s.folderName ?: "your folder")
                if (s.notesWithEdition) {
                    // Only after the edition is saved: notes that fail mustn't hold back the book.
                    val notesError = try {
                        notes.write(editionId)?.let { folder.deliver(it, folderUri, it.name, EditionNotes.MIME) }
                    } catch (e: IOException) {
                        "Couldn't write the notes (${e.message})."
                    }
                    notesError?.let { notifier.problem("Notes for ${edition.title} weren't saved", it) }
                }
            } else {
                // Left READY: the reader can still send it by hand, and nothing is used up.
                notifier.problem("${edition.title} wasn't delivered", error)
            }
        } else if (scheduled) {
            notifier.editionReady(edition, file, openInstead = s.device == Device.BOOX)
        }
    }
}
