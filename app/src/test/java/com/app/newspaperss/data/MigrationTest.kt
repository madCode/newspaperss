package com.app.newspaperss.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun seedVersion2(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                "VALUES (1, 'FEED', 'https://a.example/feed', 'A', 0, 'AUTO', 0, 0, 0, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO articles (id, sourceId, guid, url, title, feedHtml, discoveredAt, state, broughtBack, pageWords) VALUES " +
                "(1, 1, 'g1', 'https://a.example/1', 'Brought back', '<p>kept</p>', 5000, 'NEW', 1, 900), " +
                "(2, 1, 'g2', 'https://a.example/2', 'Waiting', NULL, 6000, 'NEW', 0, NULL), " +
                "(3, 1, 'g3', 'https://a.example/3', 'Delivered', NULL, 4000, 'DELIVERED', 0, NULL)",
        )
        db.execSQL("INSERT INTO editions (id, title, createdAt, status, articleCount, minutes) VALUES (1, 'Monday', 0, 'DELIVERED', 1, 3.0)")
        db.execSQL("INSERT INTO edition_articles (id, editionId, articleId, position, title, sourceTitle, minutes) VALUES (1, 1, 3, 0, 'Delivered', 'A', 3.0)")
    }

    @Test
    fun version2BroughtBackArticlesBecomeStarredAndEditionsKeepTheirArticles() {
        helper.createDatabase(DB, 2).use(::seedVersion2)

        helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT id, starredAt, state, feedHtml, pageWords FROM articles ORDER BY id").use { c ->
                c.moveToNext()
                assertEquals("starred when it was found", 5000L, c.getLong(1))
                assertEquals("NEW", c.getString(2))
                assertEquals("<p>kept</p>", c.getString(3))
                assertEquals(900, c.getInt(4))
                c.moveToNext()
                assertTrue(c.isNull(1))
                c.moveToNext()
                assertTrue(c.isNull(1))
                assertEquals("DELIVERED", c.getString(2))
            }
            db.query("SELECT articleId, starred FROM edition_articles WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(3L, c.getLong(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }

    @Test
    fun rebuildingArticlesKeepsPastEditionsLinkedEvenWithForeignKeysOn() {
        // Dropping articles with foreign keys on would null every edition_articles.articleId
        // (ON DELETE SET NULL), cutting past editions off from their articles.
        helper.createDatabase(DB, 2).use { db ->
            seedVersion2(db)
            db.execSQL("PRAGMA foreign_keys = ON")
            AppDatabase.MIGRATION_2_3.migrate(db)
            db.query("SELECT articleId FROM edition_articles WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(3L, c.getLong(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
