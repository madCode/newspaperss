package com.app.newspaperss.testutil

import androidx.room.Room
import com.app.newspaperss.AppContainer
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.data.AppDatabase

/** The app with an in-memory database, a fake network and no background work. */
class TestApp : NewspaperssApp() {
    val http = FakeHttp()

    override fun createContainer() = AppContainer(
        this,
        http = http,
        db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).allowMainThreadQueries().build(),
    )

    override fun scheduleWork() {}
}
