package com.app.newspaperss.testutil

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.app.newspaperss.data.AppDatabase
import org.junit.rules.ExternalResource

/**
 * A fresh in-memory database per test, closed afterwards. Opened on construction rather than in
 * [before] so test fields can wrap [db] in repositories as they're initialised.
 */
class DbRule : ExternalResource() {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    override fun after() = db.close()
}
