package com.app.newspaperss.edition

import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.delivery.FolderWriter
import com.app.newspaperss.notify.EditionNotifier
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.SettingsStore

/** One edition from start to finish: sync, build, deliver, tell the reader. */
class EditionRun(
    private val settings: SettingsStore,
    private val sync: FeedSync,
    private val builder: EditionBuilder,
    private val editions: EditionRepository,
    private val folder: FolderWriter,
    private val notifier: EditionNotifier,
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
            is BuildResult.Built -> deliver(result.editionId, s.delivery, s.folderUri, s.folderName, scheduled)
            is BuildResult.Failed -> if (scheduled) notifier.problem("Today's edition couldn't be made", result.reason)
            BuildResult.NothingNew -> {}
        }
        return result
    }

    private suspend fun deliver(editionId: Long, method: DeliveryMethod, folderUri: String?, folderName: String?, scheduled: Boolean) {
        val edition = editions.byId(editionId) ?: return
        val file = editions.fileOf(edition) ?: return
        if (method == DeliveryMethod.FOLDER && folderUri != null) {
            val error = folder.deliver(file, folderUri, edition.title)
            if (error == null) {
                editions.markDelivered(editionId)
                notifier.editionDelivered(edition, folderName ?: "your folder")
            } else {
                // Left READY: the reader can still send it by hand, and nothing is used up.
                notifier.problem("${edition.title} wasn't delivered", error)
            }
        } else if (scheduled) {
            notifier.editionReady(edition, file)
        }
    }
}
