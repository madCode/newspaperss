package com.app.newspaperss

import android.app.Application
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.work.SyncWorker
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
        container.notifier.createChannels()
        SyncWorker.schedulePeriodic(this)
        // Arms the timer if none is pending (first run, after an update or a
        // restore); a pending or overdue one is left alone.
        container.appScope.launch { EditionScheduler.reschedule(this@NewspaperssApp, container.settings.current()) }
    }
}
