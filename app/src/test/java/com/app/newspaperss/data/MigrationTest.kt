package com.app.newspaperss.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * Version 2 cleared an article's bring-back once it went into an edition. One brought back
     * and waiting in an unsent edition has to keep it, or it'd come back as merely waiting.
     */
    @Test
    fun anArticleBroughtBackIntoAnUnsentEditionStaysStarredAndReturnsToDelivered() {
        helper.createDatabase(DB, 2).use { db ->
            seedVersion2(db)
            db.execSQL("INSERT INTO delivered_urls (url, deliveredAt) VALUES ('https://a.example/4', 100)")
            db.execSQL(
                "INSERT INTO articles (id, sourceId, guid, url, title, discoveredAt, state, broughtBack) VALUES " +
                    "(4, 1, 'g4', 'https://a.example/4', 'Brought back, in the paper', 7000, 'IN_EDITION', 0), " +
                    "(5, 1, 'g5', 'https://a.example/5', 'New, in the paper', 8000, 'IN_EDITION', 0)",
            )
            db.execSQL("INSERT INTO editions (id, title, createdAt, status, articleCount, minutes) VALUES (2, 'Tuesday', 0, 'READY', 2, 6.0)")
            db.execSQL(
                "INSERT INTO edition_articles (id, editionId, articleId, position, title, sourceTitle, minutes) VALUES " +
                    "(2, 2, 4, 0, 'Brought back, in the paper', 'A', 3.0), (3, 2, 5, 1, 'New, in the paper', 'A', 3.0)",
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals(7000L, room.articles().byId(4)!!.starredAt?.toEpochMilli())
                assertNull(room.articles().byId(5)!!.starredAt)
                assertTrue(EditionRepository(room, File("unused")).delete(2))
                assertEquals(ArticleState.DELIVERED, room.articles().byId(4)!!.state)
                assertEquals(ArticleState.NEW, room.articles().byId(5)!!.state)
                assertTrue("still first in line", room.articles().candidates().any { it.id == 4L && it.starredAt != null })
            }
        } finally {
            room.close()
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

    @Test
    fun version3ArticlesSurviveAsOrdinaryArticles() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                    "VALUES (1, 'FEED', 'https://a.example/feed', 'A', 0, 'AUTO', 0, 0, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO articles (id, sourceId, guid, url, title, discoveredAt, state, starredAt) VALUES (7, 1, 'g', 'https://a.example/1', 'Kept', 0, 'NEW', 50)")
        }

        helper.runMigrationsAndValidate(DB, 4, true, AppDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT url, starredAt, viaUrl FROM articles WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals("https://a.example/1", c.getString(0))
                assertEquals(50L, c.getLong(1))
                assertTrue(c.isNull(2))
            }
        }
    }

    @Test
    fun version4GainsLeftOutFeedsThatGoWithTheirSource() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                    "VALUES (1, 'TTRSS', 'https://rss.example/api/', 'Tiny Tiny RSS', 0, 'AUTO', 0, 0, 0, 1, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB, 5, true, AppDatabase.MIGRATION_4_5).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO left_out_feeds (sourceId, originId, title) VALUES (1, '42', 'Press releases')")
            db.execSQL("DELETE FROM sources WHERE id = 1")
            db.query("SELECT COUNT(*) FROM left_out_feeds").use { c ->
                c.moveToFirst()
                assertEquals("removing the account forgets its choices", 0, c.getInt(0))
            }
        }
    }

    private fun rows(c: android.database.Cursor) = generateSequence {
        if (c.moveToNext()) (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) } else null
    }.toList()

    /** What each feed learned and what the reader chose move to its own publication; sections aren't kept. */
    @Test
    fun version6MovesWhatEachSourceLearnedAndChoseToItsOwnPublication() {
        helper.createDatabase(DB, 6).use { db ->
            fun source(id: Int, kind: String, mode: String, chosen: Int, evidence: String?, max: Int?, section: String?) = db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextEvidence, fullTextStreak, fullTextDay, " +
                    "paused, markReadOnServer, addedAt, maxArticles, section) VALUES ($id, '$kind', 'https://s$id.example/feed', 'S$id', $id, '$mode', $chosen, " +
                    "${evidence?.let { "'$it'" } ?: "NULL"}, 3, 20000, 0, 1, 0, ${max ?: "NULL"}, ${section?.let { "'$it'" } ?: "NULL"})",
            )
            source(1, "FEED", "FEED", 0, "BLOCKED", null, null)
            source(2, "FEED", "PAGE", 1, null, 2, "World")
            source(3, "FEED", "AUTO", 0, null, null, "World")
            source(4, "READING_LIST", "PAGE", 0, null, null, "Saved for later")
            source(5, "LIST", "PAGE", 0, null, 1, null)
            source(6, "TTRSS", "AUTO", 0, null, 3, null)
        }

        helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT sourceId, `key`, contentMode, fullTextEvidence, fullTextStreak, checkedDay, chosenMode, maxArticles FROM publications ORDER BY sourceId").use { c ->
                assertEquals(
                    listOf(
                        listOf("1", "", "FEED", "BLOCKED", "3", null, null, null),
                        listOf("2", "", "AUTO", null, "0", null, "PAGE", "2"),
                        listOf("5", "", "AUTO", null, "0", null, null, "1"),
                    ),
                    // No row for tt-rss: its feeds are capped one by one, so a cap on the account never applied.
                    rows(c),
                )
            }
            db.query("SELECT contentMode, contentModeChosen, fullTextEvidence, maxArticles, section, feedsListedAt FROM sources ORDER BY id").use { c ->
                assertEquals(
                    "the reading list and curated lists still fetch pages; nothing else is left on the source",
                    listOf("AUTO", "AUTO", "AUTO", "PAGE", "PAGE", "AUTO").map { listOf(it, "0", null, null, null, null) },
                    rows(c),
                )
            }
        }
    }

    @Test
    fun version6LeftOutFeedsBecomeLeftOutPublications() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                    "VALUES (1, 'TTRSS', 'https://rss.example/api/', 'Tiny Tiny RSS', 0, 'AUTO', 0, 0, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO left_out_feeds (sourceId, originId, title) VALUES (1, '42', 'Press releases')")
        }

        helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT `key`, contentMode, title, leftOut, listed FROM publications").use { c ->
                assertEquals(listOf(listOf("42", "AUTO", "Press releases", "1", "0")), rows(c))
            }
            db.query("SELECT name FROM sqlite_master WHERE name = 'left_out_feeds'").use { c -> assertEquals(0, c.count) }
        }
    }

    @Test
    fun version5SourcesDontSkipPaidPostsAndNoArticleIsOne() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, kind, url, title, position, contentMode, contentModeChosen, fullTextStreak, paused, markReadOnServer, addedAt) " +
                    "VALUES (1, 'FEED', 'https://a.example/feed', 'A', 0, 'AUTO', 0, 0, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO articles (id, sourceId, guid, url, title, discoveredAt, state, reportedRead) VALUES (7, 1, 'g', 'https://a.example/1', 'Kept', 0, 'NEW', 0)")
        }

        helper.runMigrationsAndValidate(DB, 6, true, AppDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT skipPaidPosts FROM sources WHERE id = 1").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT paidOnly, paidSkipped, state FROM articles WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals(0, c.getInt(1))
                assertEquals("NEW", c.getString(2))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
