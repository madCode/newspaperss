package com.app.newspaperss.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.app.newspaperss.testutil.TestApp

/** Debug builds are installed and in use, so every schema change has to carry their data forward. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1ArticlesSurviveAndGainPageWords() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                    "VALUES (1, 'FEED', 'https://a.example/feed', 'A', 0, 'AUTO', 0, 0, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO articles (id, sourceId, guid, url, title, discoveredAt, state, broughtBack) VALUES (7, 1, 'g', 'https://a.example/1', 'Kept', 0, 'NEW', 0)")
        }

        helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT title, pageWords FROM articles WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals("Kept", c.getString(0))
                assertEquals(true, c.isNull(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
