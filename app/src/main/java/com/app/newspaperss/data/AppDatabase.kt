package com.app.newspaperss.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant

class Converters {
    @TypeConverter fun instantToLong(i: Instant?): Long? = i?.toEpochMilli()
    @TypeConverter fun longToInstant(l: Long?): Instant? = l?.let(Instant::ofEpochMilli)
}

@Database(
    entities = [SourceEntity::class, ArticleEntity::class, EditionEntity::class, EditionArticleEntity::class, DeliveredUrlEntity::class, LeftOutFeedEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun articles(): ArticleDao
    abstract fun editions(): EditionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN pageWords INTEGER")
            }
        }

        /**
         * `articles.broughtBack` becomes `starredAt` (a brought-back article is a starred one,
         * starred when it was found), and edition articles remember whether they were starred.
         * SQLite before 3.35 (Android before 14) can't drop a column, so articles is rebuilt.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room turns foreign keys on only after migrating, but if they were on, dropping
                // articles would set every past edition's articleId to null. Kept aside to be sure.
                db.execSQL("CREATE TEMP TABLE edition_article_links AS SELECT id, articleId FROM edition_articles WHERE articleId IS NOT NULL")
                db.execSQL(
                    "CREATE TABLE articles_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "`guid` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `published` INTEGER, " +
                        "`feedHtml` TEXT, `discoveredAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `starredAt` INTEGER, " +
                        "`originId` TEXT, `originTitle` TEXT, `pageWords` INTEGER, `reportedRead` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO articles_new (id, sourceId, guid, url, title, author, published, feedHtml, discoveredAt, state, starredAt, originId, originTitle, pageWords) " +
                        "SELECT id, sourceId, guid, url, title, author, published, feedHtml, discoveredAt, state, " +
                        "CASE WHEN broughtBack != 0 THEN discoveredAt END, originId, originTitle, pageWords FROM articles",
                )
                db.execSQL("DROP TABLE articles")
                db.execSQL("ALTER TABLE articles_new RENAME TO articles")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_articles_sourceId_guid` ON `articles` (`sourceId`, `guid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_url` ON `articles` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_state` ON `articles` (`state`)")
                db.execSQL(
                    "UPDATE edition_articles SET articleId = (SELECT articleId FROM edition_article_links WHERE edition_article_links.id = edition_articles.id) " +
                        "WHERE id IN (SELECT id FROM edition_article_links)",
                )
                db.execSQL("DROP TABLE edition_article_links")
                db.execSQL("ALTER TABLE edition_articles ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE edition_articles ADD COLUMN `stateBefore` TEXT")
                // An article in an unsent edition whose link was already delivered was brought back
                // (version 2 cleared the flag once it went in). It stays starred, so if the edition is
                // never sent it keeps its place and returns to delivered rather than being lost.
                db.execSQL(
                    "UPDATE articles SET starredAt = discoveredAt WHERE state = 'IN_EDITION' AND url != '' " +
                        "AND url IN (SELECT url FROM delivered_urls)",
                )
                db.execSQL(
                    "UPDATE edition_articles SET starred = 1, stateBefore = 'DELIVERED' " +
                        "WHERE editionId IN (SELECT id FROM editions WHERE status = 'READY') " +
                        "AND articleId IN (SELECT id FROM articles WHERE state = 'IN_EDITION' AND starredAt IS NOT NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN viaUrl TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `left_out_feeds` (`sourceId` INTEGER NOT NULL, `originId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `originId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "newspaperss.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
