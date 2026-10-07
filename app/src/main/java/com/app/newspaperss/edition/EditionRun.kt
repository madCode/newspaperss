package com.app.newspaperss.edition

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.delivery.FolderWriter
import com.app.newspaperss.notify.EditionNotifier
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/** One edition from start to finish: sync, build, deliver, tell the reader. */
class EditionRun(
    private val settings: SettingsStore,
    private val sync: FeedSync,
    private val builder: EditionBuilder,
    private val editions: EditionRepository,
    private val folder: FolderWriter,
    private val notifier: EditionNotifier,
    private val now: () -> Instant = Instant::now,
    /** An edition was built and delivered (or left to send): its podcast is asked for here. */
    private val onBuilt: suspend (editionId: Long, scheduled: Boolean) -> Unit = { _, _ -> },
) {
    /**
     * @param scheduled true for the timed run. Only then does a shared edition
     *   get a "ready" notification; someone who tapped "Make one now" is
     *   already looking at it.
     * @param dueAt when a timed edition is due, which is what it's titled and dated for.
     * @param deadline when to stop fetching articles and write the edition with what it has.
     * @param finalAttempt false when the worker will retry an [BuildResult.Unreachable] timed run,
     *   so the reader isn't told of a failure that a retry minutes later may undo.
     */
    suspend fun run(
        scheduled: Boolean, onProgress: (Int) -> Unit = {}, dueAt: Instant? = null, finalAttempt: Boolean = true, deadline: Instant? = null,
    ): BuildResult {
        val s = settings.current()
        val synced = sync.syncAll()
        val built = builder.build(s.edition, dueAt, deadline, onProgress)
        // "Nothing new" when every source failed would hide the failure.
        val result = if (built == BuildResult.NothingNew && synced.sources > 0 && synced.failedSources == synced.sources) {
            BuildResult.Unreachable(synced.sources)
        } else built
        when (result) {
            is BuildResult.Built -> {
                deliver(result.editionId, s, scheduled)
                // The paper is made and delivered: nothing after it can fail the run.
                try {
                    onBuilt(result.editionId, scheduled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("EditionRun", "After the build: ${e.javaClass.name}")
                }
            }
            is BuildResult.Failed -> if (scheduled) notifier.problem("Today's edition couldn't be made", result.reason)
            is BuildResult.Unreachable -> if (scheduled && finalAttempt) notifier.problem("No new edition", result.reason)
            // Quiet, but said: otherwise a timed paper that doesn't come looks like the app broke.
            // Not when one came recently (made by hand just before): that one is the news.
            BuildResult.NothingNew -> if (scheduled) {
                val made = editions.observeAll().first().filter { it.status == EditionStatus.READY || it.status == EditionStatus.DELIVERED }
                val latest = made.firstOrNull()
                if (latest == null || latest.createdAt.isBefore(now().minus(RECENT))) notifier.nothingNew(firstEver = latest == null)
            }
        }
        editions.pruneFiles()
        return result
    }

    private companion object {
        val RECENT: Duration = Duration.ofHours(12)
    }

    private suspend fun deliver(editionId: Long, s: Settings, scheduled: Boolean) {
        val edition = editions.byId(editionId) ?: return
        val file = editions.fileOf(edition) ?: return
        val folderUri = s.folderUri
        if (s.delivery == DeliveryMethod.FOLDER && folderUri != null) {
            // Copied and marked delivered together: cancelled between the two (WorkManager's time
            // limit, the network constraint lost), the book would be in the folder while the
            // edition stayed unsent, and the next build would put the same articles in another.
            val error = withContext(NonCancellable) {
                folder.deliver(file, folderUri, FolderDelivery.fileName(edition.title), EditionIntents.EPUB_MIME)
                    .also { if (it == null) editions.markDelivered(editionId) }
            }
            if (error == null) {
                // Deleted while its file was being copied: no news about an edition that's gone.
                if (editions.byId(editionId)?.status != EditionStatus.DELIVERED) return
                notifier.editionDelivered(edition, s.folderName ?: "your folder")
            } else {
                // Left READY: the reader can still send it by hand, and nothing is used up.
                notifier.problem("${edition.title} wasn't delivered", error)
            }
        } else if (scheduled && editions.byId(editionId)?.status == EditionStatus.READY) {
            notifier.editionReady(edition, file, openInstead = s.device == Device.BOOX, byEmail = s.kindleEmailTarget != null)
        }
    }
}
