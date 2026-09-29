package com.app.newspaperss

import android.app.Application
import com.app.newspaperss.work.SyncWorker

class NewspaperssApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedulePeriodic(this)
    }
}
