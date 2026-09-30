package com.app.newspaperss.edition

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.notify.EditionNotifier
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

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class NotesSaverTest {
    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") }) }
    private val delivered = mutableListOf<Long>()
    private val editions by lazy { EditionRepository(db, tmp.newFolder("editions"), onDelivered = { delivered += it }) }
    private val problems = mutableListOf<String>()
    private val notifier = object : EditionNotifier {
        override fun editionReady(edition: com.app.newspaperss.data.EditionEntity, file: File, openInstead: Boolean) {}
        override fun editionDelivered(edition: com.app.newspaperss.data.EditionEntity, where: String) {}
        override fun problem(title: String, reason: String) { problems += "$title: $reason" }
        override fun dismissFor(editionId: Long) {}
        override fun nothingNew(firstEver: Boolean) {}
    }
    private val folderErrors = mutableMapOf<String, String>()
    private val saved = mutableListOf<String>()
    private val notes by lazy { EditionNotes(db, tmp.newFolder("notes")) }
    private val saver by lazy {
        NotesSaver(settings, editions, notes, { file, uri, name, mime -> saved += "$uri/$name ($mime)"; folderErrors[uri] }, notifier)
    }

    /** A shared edition, built and marked sent. */
    private suspend fun sentEdition(): Pair<Long, String> {
        SourceRepository(db).addFeed("https://example.com/feed", "Blog")
        http.page("https://example.com/feed", rss("Blog", "1" to "One"))
        FeedSync(db, http).syncAll()
        val content = ArticleContentProvider { a, _, _ -> ArticleContent(a.title, null, "<p>body</p>", 500) }
        val id = (EditionBuilder(db, content, editions.editionsDir).build(com.app.newspaperss.edition.EditionSettings()) as BuildResult.Built).editionId
        editions.markSent(id)
        return id to db.editions().byId(id)!!.title
    }

    @Test
    fun aDeliveredEditionsNotesGoToTheNotesFolder() = runTest {
        settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
        val (id, title) = sentEdition()
        assertEquals("delivery asks for the notes", listOf(id), delivered)

        saver.save(id)

        assertEquals(listOf("content://vault/$title notes.md (text/markdown)"), saved)
        assertTrue(File(notes.notesDir, "saved/$title notes.md").readText().contains("## One"))
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun anEditionDeliveredTwiceSavesItsNotesOnce() = runTest {
        // Tapped Sent while its folder copy was finishing, which then reports it delivered too.
        val (id, _) = sentEdition()
        editions.markDelivered(id)
        assertEquals(listOf(id), delivered)
    }

    @Test
    fun noNotesFolderNoNotes() = runTest {
        val (id, _) = sentEdition()
        saver.save(id)
        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun anEditionDeletedBeforeItsNotesWereSavedGetsNone() = runTest {
        settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
        val (id, _) = sentEdition()
        editions.delete(id)

        saver.save(id)

        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun aFolderThatRefusesTheNotesIsReported() = runTest {
        settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
        folderErrors["content://vault"] = "folder full"
        val (id, title) = sentEdition()

        saver.save(id)

        assertEquals(listOf("Notes for $title weren't saved: folder full"), problems)
    }

    @Test
    fun notesThatCantBeWrittenLocallyAreReportedNotThrown() = runTest {
        settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
        val (id, _) = sentEdition()
        notes.notesDir.delete()
        notes.notesDir.writeText("a file where the folder should be")

        saver.save(id)

        assertTrue(problems.single().contains("Couldn't write the notes"))
    }
}
