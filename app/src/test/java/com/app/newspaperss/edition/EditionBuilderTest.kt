package com.app.newspaperss.edition

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-09-29T06:30:00Z"), ZoneOffset.UTC)
    private val sources = SourceRepository(db)
    private val unreadable = mutableSetOf<String>()
    private val content = ArticleContentProvider { a, _, _ ->
        if (a.guid in unreadable) null else ArticleContent(a.title, null, "<p>${a.title} body</p>", wordCount = 2000)
    }
    private val builder by lazy { EditionBuilder(db, content, tmp.root, clock, ZoneOffset.UTC) }
    private val editions by lazy { EditionRepository(db, tmp.root, clock) }

    @After fun close() = db.close()

    private suspend fun source(name: String, section: String? = null, vararg guids: String): Long {
        val id = sources.addFeed("https://$name.example/feed", name, section)
        db.articles().insertNew(
            guids.mapIndexed { i, g ->
                ArticleEntity(
                    sourceId = id, guid = g, url = "https://$name.example/$g", title = "$name $g",
                    published = Instant.parse("2026-09-2${i}T00:00:00Z"),
                )
            },
        )
        return id
    }

    private suspend fun stateOf(guid: String) = db.articles().candidates().none { it.guid == guid }.let { notNew ->
        if (!notNew) ArticleState.NEW else db.query("SELECT state FROM articles WHERE guid = ?", arrayOf(guid)).use { c ->
            c.moveToFirst(); ArticleState.valueOf(c.getString(0))
        }
    }

    @Test
    fun buildsAnEpubWithinTheBudgetTakingTurnsAndGroupingBySection() = runTest {
        source("a", "World", "a1", "a2")
        source("b", "Culture", "b1")
        source("c", "World", "c1")

        val result = builder.build(EditionSettings(minutes = 25, maxPerSource = 1, wordsPerMinute = 200)) as BuildResult.Built

        val edition = db.editions().byId(result.editionId)!!
        assertEquals("Tuesday Morning Edition", edition.title)
        assertEquals(EditionStatus.READY, edition.status)
        assertEquals(3, edition.articleCount)
        val titles = editions.observeArticles(edition.id).first().map { it.title }
        assertEquals("sections group the reading order", listOf("a a2", "c c1", "b b1"), titles)

        ZipFile(editions.fileOf(edition)!!).use { zip ->
            assertTrue(zip.entries().toList().any { it.name.endsWith(".xhtml") })
        }
        assertEquals(ArticleState.IN_EDITION, stateOf("a2"))
        assertEquals("the second article of a capped source waits", ArticleState.NEW, stateOf("a1"))
    }

    @Test
    fun articlesAreOnlyUsedUpOnceDelivered() = runTest {
        source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        assertEquals(ArticleState.IN_EDITION, stateOf("a1"))

        editions.markDelivered(first.editionId)
        assertEquals(ArticleState.DELIVERED, stateOf("a1"))
        assertEquals(EditionStatus.DELIVERED, db.editions().byId(first.editionId)!!.status)
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun anEditionNeverSentGivesItsArticlesToTheNextOne() = runTest {
        source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        source("b", null, "b1")

        val second = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(EditionStatus.FAILED, db.editions().byId(first.editionId)!!.status)
        val titles = editions.observeArticles(second.editionId).first().map { it.title }.toSet()
        assertEquals(setOf("a a1", "b b1"), titles)
        assertEquals("Tuesday Morning Edition (2)", db.editions().byId(second.editionId)!!.title)
    }

    @Test
    fun unreadableArticlesAreSkippedAndAnAllUnreadableEditionFails() = runTest {
        source("a", null, "a1", "a2")
        unreadable += "a2"
        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built
        assertEquals(listOf("a a1"), editions.observeArticles(built.editionId).first().map { it.title })

        unreadable += "b1"
        source("b", null, "b1")
        db.articles().setState(listOf(db.articles().candidates().first { it.guid == "a2" }.id), ArticleState.SKIPPED)
        editions.markDelivered(built.editionId)
        val failed = builder.build(EditionSettings()) as BuildResult.Failed
        assertEquals(EditionStatus.FAILED, db.editions().byId(failed.editionId)!!.status)
        assertEquals(ArticleState.NEW, stateOf("b1"))
    }

    @Test
    fun pausedSourcesAreLeftOut() = runTest {
        val id = source("a", null, "a1")
        sources.update(db.sources().byId(id)!!.copy(paused = true))
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun bringBackPutsArticlesFirstInLine() = runTest {
        source("a", null, "a1", "a2")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(first.editionId)
        val delivered = db.editions().articleIds(first.editionId)
        editions.bringBack(delivered)

        val second = builder.build(EditionSettings()) as BuildResult.Built
        assertEquals(delivered, db.editions().articleIds(second.editionId))
    }

    @Test
    fun imagesPastTheEditionBudgetAreLeftOutInReadingOrder() = runTest {
        source("a", null, "a1", "a2")
        val withImage = ArticleContentProvider { a, _, _ ->
            val href = "images/a${a.id}-1.jpg"
            ArticleContent(
                a.title, null, "<p>${a.title}</p><figure><img src=\"$href\"/><figcaption>${a.guid} caption</figcaption></figure>",
                wordCount = 238, images = listOf(EpubImage(href, "image/jpeg", ByteArray(60))),
            )
        }
        val built = EditionBuilder(db, withImage, tmp.root, clock, ZoneOffset.UTC, imageBudgetBytes = 100)
            .build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        val edition = db.editions().byId(built.editionId)!!
        ZipFile(editions.fileOf(edition)!!).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(1, names.count { it.startsWith("OEBPS/images/") })
            val chapters = zip.entries().toList().filter { it.name.endsWith(".xhtml") }
                .map { String(zip.getInputStream(it).readBytes()) }
            assertEquals("only the first article in reading order keeps its image", 1, chapters.count { "<img" in it })
            assertEquals(1, chapters.count { "caption</figcaption>" in it })
        }
    }

    @Test
    fun removingASourceKeepsPastEditionsContents() = runTest {
        val id = source("a", null, "a1")
        val built = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(built.editionId)

        sources.remove(db.sources().byId(id)!!)

        assertEquals(listOf("a a1"), editions.observeArticles(built.editionId).first().map { it.title })
    }
}
