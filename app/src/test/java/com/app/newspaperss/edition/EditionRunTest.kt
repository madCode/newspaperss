package com.app.newspaperss.edition

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.notify.EditionNotifier
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionRunTest {
    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") }) }
    private val editions by lazy { EditionRepository(db, tmp.newFolder("editions")) }
    private val notices = mutableListOf<String>()
    private val notifier = object : EditionNotifier {
        override fun editionReady(edition: EditionEntity, file: File, openInstead: Boolean) { notices += if (openInstead) "open ${edition.title}" else "ready ${edition.title}" }
        override fun editionDelivered(edition: EditionEntity, where: String) { notices += "delivered to $where" }
        override fun problem(title: String, reason: String) { notices += "problem: $reason" }
        override fun nothingNew(firstEver: Boolean) { notices += if (firstEver) "nothing yet" else "nothing new" }
    }
    private val folderErrors = mutableMapOf<String, String>()
    private var clock = Instant.now()
    private val saved = mutableListOf<String>()
    private val notes by lazy { EditionNotes(db, tmp.newFolder("notes")) }
    private val run by lazy {
        val content = ArticleContentProvider { a, _, _ -> ArticleContent(a.title, null, "<p>body</p>", 500) }
        EditionRun(
            settings, FeedSync(db, http), EditionBuilder(db, content, editions.editionsDir), editions,
            { file, uri, name, mime -> saved += "$uri/$name ($mime):${file.length() > 0}"; folderErrors[mime] }, notifier, notes,
            now = { clock },
        )
    }

    private suspend fun oneSource() {
        SourceRepository(db).addFeed("https://example.com/feed", "Blog")
        http.page("https://example.com/feed", rss("Blog", "1" to "One"))
    }

    private suspend fun status(result: BuildResult) = db.editions().byId((result as BuildResult.Built).editionId)!!.status

    @Test
    fun whenEverySourceFailsItSaysSoRatherThanNothingNew() = runTest {
        SourceRepository(db).addFeed("https://example.com/feed", "Blog")  // no page: the fetch fails

        val result = run.run(scheduled = true)

        assertEquals(BuildResult.Unreachable(1), result)
        assertEquals(listOf("problem: None of your 1 source could be read. Sources shows what went wrong with each."), notices)
    }

    @Test
    fun aTimedRunThatWillBeRetriedKeepsQuietAboutUnreadableSources() = runTest {
        SourceRepository(db).addFeed("https://example.com/feed", "Blog")

        assertEquals(BuildResult.Unreachable(1), run.run(scheduled = true, finalAttempt = false))
        assertEquals(emptyList<String>(), notices)
    }

    @Test
    fun oneSourceFailingIsntAllOfThemFailing() = runTest {
        oneSource()
        SourceRepository(db).addFeed("https://example.com/other", "Other")  // fails
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo") }
        run.run(scheduled = false)

        assertEquals(BuildResult.NothingNew, run.run(scheduled = false))
    }

    @Test
    fun withNoSourcesAtAllThereIsNothingToReadNotAFailure() = runTest {
        assertEquals(BuildResult.NothingNew, run.run(scheduled = true))
        assertEquals(listOf("nothing yet"), notices)
    }

    @Test
    fun aTimedRunWithNothingNewSaysSoQuietly() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo") }
        run.run(scheduled = true)

        // A timed run just after an edition came (one made by hand minutes before): that one is the news.
        assertEquals(BuildResult.NothingNew, run.run(scheduled = true))
        assertEquals(listOf("delivered to Kobo"), notices)

        clock = clock.plus(java.time.Duration.ofDays(1))
        assertEquals(BuildResult.NothingNew, run.run(scheduled = true))
        assertEquals(listOf("delivered to Kobo", "nothing new"), notices)

        // Made by hand, the reader is looking at Today already.
        assertEquals(BuildResult.NothingNew, run.run(scheduled = false))
        assertEquals(listOf("delivered to Kobo", "nothing new"), notices)
    }

    @Test
    fun folderDeliveryMarksTheEditionDelivered() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo") }

        val result = run.run(scheduled = true)

        assertEquals(EditionStatus.DELIVERED, status(result))
        assertEquals(listOf("content://tree/${db.editions().byId((result as BuildResult.Built).editionId)!!.title}.epub (application/epub+zip):true"), saved)
        assertEquals(listOf("delivered to Kobo"), notices)
        assertTrue(db.articles().candidates().isEmpty())
    }

    @Test
    fun aFailedFolderSaveLeavesTheEditionReadyAndSaysWhy() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree") }
        folderErrors[EditionIntents.EPUB_MIME] = "no access"

        val result = run.run(scheduled = false)

        assertEquals(EditionStatus.READY, status(result))
        assertEquals(listOf("problem: no access"), notices)
    }

    @Test
    fun notesAreSavedBesideTheEditionWhenAskedFor() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo", notesWithEdition = true) }

        val result = run.run(scheduled = true)

        val title = db.editions().byId((result as BuildResult.Built).editionId)!!.title
        assertEquals(
            listOf("content://tree/$title.epub (application/epub+zip):true", "content://tree/$title notes.md (text/markdown):true"),
            saved,
        )
        assertTrue(File(notes.notesDir, "$title notes.md").readText().contains("## One"))
        assertEquals(listOf("delivered to Kobo"), notices)
    }

    @Test
    fun notesThatCantBeSavedDontUndoTheDelivery() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo", notesWithEdition = true) }
        folderErrors[EditionNotes.MIME] = "folder full"

        val result = run.run(scheduled = true)

        assertEquals(EditionStatus.DELIVERED, status(result))
        assertEquals(listOf("delivered to Kobo", "problem: folder full"), notices)
    }

    @Test
    fun notesThatCantBeWrittenLocallyAreReportedNotThrown() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Kobo", notesWithEdition = true) }
        notes.notesDir.delete()
        notes.notesDir.writeText("a file where the folder should be")

        val result = run.run(scheduled = true)

        assertEquals(EditionStatus.DELIVERED, status(result))
        assertEquals("delivered to Kobo", notices[0])
        assertTrue(notices[1].startsWith("problem: Couldn't write the notes"))
    }

    @Test
    fun notesStayOutOfTheFolderUnlessAskedFor() = runTest {
        oneSource()
        settings.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree") }

        run.run(scheduled = true)

        assertEquals(1, saved.size)
        assertTrue(saved.single().endsWith(".epub (application/epub+zip):true"))
    }

    @Test
    fun sharedEditionsNotifyOnlyWhenScheduled() = runTest {
        oneSource()
        run.run(scheduled = false)
        assertEquals(emptyList<String>(), notices)

        http.page("https://example.com/feed", rss("Blog", "2" to "Two"))
        run.run(scheduled = true)
        assertEquals(1, notices.size)
        assertTrue(notices.single().startsWith("ready"))
    }

    @Test
    fun aScheduledRunThatFailsIsLoud() = runTest {
        SourceRepository(db).addFeed("https://example.com/feed", "Blog")
        http.page("https://example.com/feed", rss("Blog", "1" to "One"))
        val failing = EditionRun(
            settings, FeedSync(db, http), EditionBuilder(db, { _, _, _ -> null }, editions.editionsDir), editions, { _, _, _, _ -> null }, notifier, notes,
        )
        failing.run(scheduled = true)
        assertEquals(listOf("problem: None of the articles could be read."), notices)
    }

    @Test
    fun aBooxReaderIsOfferedOpenNotSend() = runTest {
        oneSource()
        settings.update { it.copy(device = com.app.newspaperss.settings.Device.BOOX) }
        run.run(scheduled = true)
        assertTrue(notices.single().startsWith("open"))
    }
}
