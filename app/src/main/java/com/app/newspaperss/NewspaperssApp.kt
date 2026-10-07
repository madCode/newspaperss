package com.app.newspaperss

import android.content.Context
import android.app.Application
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.work.SyncWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

open class NewspaperssApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        scheduleWork()
    }

    protected open fun createContainer() = AppContainer(this)

    protected open fun scheduleWork() {
        // With the start-up work tests leave out: every test app writing settings at start
        // would race the deletion of the last test's files. Screens don't wait for it.
        container.appScope.launch { container.settleFeedsFrom() }
        // WorkManager keeps work it was given, but not a batch stored just before the app died.
        container.appScope.launch { container.feedMoves.resumeIfPending() }
        container.notifier.createChannels()
        SyncWorker.schedulePeriodic(this)
        // Arms the timer if none is pending (first run, after an update or a
        // restore); a pending or overdue one is left alone. Again whenever the head start
        // for the podcast changes: its pace learned from a podcast, or Kokoro turned on or off.
        container.appScope.launch {
            container.settings.settings.map { EditionScheduler.lead(it) }.distinctUntilChanged().collect {
                EditionScheduler.reschedule(this@NewspaperssApp, container.settings.current())
            }
        }
    }
}

/** The app's services, from anything with a context: workers, receivers, services and activities. */
val Context.container: AppContainer get() = (applicationContext as NewspaperssApp).container
