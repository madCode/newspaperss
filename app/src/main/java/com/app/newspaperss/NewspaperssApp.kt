package com.app.newspaperss

import android.app.Application
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.work.SyncWorker
import kotlinx.coroutines.MainScope
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
        container.notifier.createChannels()
        SyncWorker.schedulePeriodic(this)
        // Arms the timer if none is pending (first run, after an update or a
        // restore); a pending or overdue one is left alone.
        MainScope().launch { EditionScheduler.reschedule(this@NewspaperssApp, container.settings.current()) }
    }
}
