package com.app.newspaperss

import android.app.Application
import com.app.newspaperss.work.SyncWorker

open class NewspaperssApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        scheduleWork()
    }

    protected open fun createContainer() = AppContainer(this)

    protected open fun scheduleWork() = SyncWorker.schedulePeriodic(this)
}
