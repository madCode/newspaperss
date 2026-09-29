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
        // Re-arm after an app update or a restore; WorkManager itself survives reboots.
        MainScope().launch { EditionScheduler.reschedule(this@NewspaperssApp, container.settings.current()) }
    }
}
