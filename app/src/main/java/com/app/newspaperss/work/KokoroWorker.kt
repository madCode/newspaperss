package com.app.newspaperss.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.listen.KokoroDownload
import com.app.newspaperss.listen.PodcastSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the Kokoro voice and checks how fast this phone runs it ([PodcastSetup.downloadAndCheck]).
 * Stopped by Android's time limit on work or a lost connection, it carries on where it was when the
 * work runs again.
 */
class KokoroWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NewspaperssApp).container
        return try {
            container.podcastSetup.downloadAndCheck(
                container.kokoroDownload(),
                onProgress = { got, total -> setProgressAsync(workDataOf(PodcastSetup.PHASE to PodcastSetup.PHASE_DOWNLOAD, PodcastSetup.GOT to got, PodcastSetup.TOTAL to total)) },
                onChecking = { setProgress(workDataOf(PodcastSetup.PHASE to PodcastSetup.PHASE_CHECK)) },
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: KokoroDownload.NoSpaceException) {
            fail("Not enough space: Kokoro needs ${e.bytes / 1_000_000} MB free. Free some up, then try again.")
        } catch (e: IOException) {
            // A dropped connection, Hugging Face out for a while, or a file damaged on the way: what's
            // arrived is kept. Only failures count; Android stopping the work (its time limit, Wi-Fi
            // lost) doesn't, so the run count can't be used.
            // Stopping closes the connection, which ends the read with an IOException: not a failure.
            if (isStopped) throw CancellationException("Stopped")
            val failures = container.kokoroInstall.failed()
            when {
                failures < ATTEMPTS -> Result.retry()
                e is KokoroDownload.DamagedException -> fail("Kokoro kept arriving damaged. Try again later.")
                else -> fail("Couldn't download Kokoro. Check your connection and try again.")
            }
        } catch (e: Exception) {
            fail("Kokoro couldn't start on this phone.")
        } catch (e: LinkageError) {
            // Its native code didn't load.
            fail("Kokoro couldn't start on this phone.")
        }
    }

    private fun fail(message: String) = Result.failure(workDataOf(PodcastSetup.ERROR to message))

    companion object {
        private const val UNIQUE = "kokoro"
        /** Failures in a row, with nothing arriving in between, before it gives up. */
        internal const val ATTEMPTS = 6

        /** Wi-Fi only unless [mobileData]; asking again replaces a download waiting for Wi-Fi. */
        fun enqueue(context: Context, mobileData: Boolean) {
            val request = OneTimeWorkRequestBuilder<KokoroWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }

        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { it.lastOrNull() }
    }
}
