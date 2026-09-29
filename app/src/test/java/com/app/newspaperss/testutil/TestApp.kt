package com.app.newspaperss.testutil

import androidx.room.Room
import com.app.newspaperss.AppContainer
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.data.AppDatabase

/**
 * The app with an in-memory database, a fake network, a software cipher in place of the
 * Android Keystore (Robolectric has none) and no background work.
 */
class TestApp : NewspaperssApp() {
    val http = FakeHttp()
    /** Editions [com.app.newspaperss.data.EditionRepository] asked to mark read in tt-rss. */
    val markedTtrssRead = mutableListOf<Long>()
    /** Reading-list articles whose titles the app asked to look up in the background. */
    val titlesRequested = mutableListOf<Long>()

    override fun createContainer() = AppContainer(
        this,
        http = http,
        db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).allowMainThreadQueries().build(),
        cipher = testCipher(),
        markTtrssRead = { markedTtrssRead += it },
        fetchReadingListTitles = { titlesRequested += it },
    )

    override fun scheduleWork() {}
}
