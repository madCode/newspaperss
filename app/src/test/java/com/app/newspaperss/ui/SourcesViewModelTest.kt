package com.app.newspaperss.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.StoredAccount
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.TtrssForm
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import com.app.newspaperss.ui.sources.AddState
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesViewModelTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @get:Rule val tmp = TemporaryFolder()

    @After fun close() = db.close()

    @Test
    fun theReadingListIsntListedAsAFeed() {
        runBlocking {
            ReadingListRepository(db).save("https://a.example/1")
            SourceRepository(db).addFeed("https://b.example/feed", "B")
        }
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(FakeHttp())) {}
        vm.rows.launchIn(kotlinx.coroutines.MainScope())
        idleUntil { vm.rows.value != null }
        assertEquals(listOf("B"), vm.rows.value!!.map { it.source.title })
    }

    @Test
    fun aSiteWithNoFeedCanBeSavedToTheReadingListInstead() {
        val http = FakeHttp().apply { page("https://blog.example", "<html><body><p>No feed here.</p></body></html>") }
        val reading = ReadingListRepository(db)
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(http), saveToReadingList = { reading.save(it) }) {}

        vm.openAdd()
        vm.editInput("blog.example")
        vm.find()
        idleUntil { (vm.add.value as? AddState.Editing)?.error != null }
        assertEquals("https://blog.example", (vm.add.value as AddState.Editing).page)

        vm.saveInstead()
        idleUntil { vm.message.value != null }
        assertEquals(AddState.Closed, vm.add.value)
        assertEquals("Saved to your reading list", vm.message.value)
        assertEquals(listOf("https://blog.example"), runBlocking { reading.observe().first() }.map { it.url })
    }

    @Test
    fun anAddressThatCantBeReachedIsntOfferedForTheReadingList() {
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(FakeHttp()), saveToReadingList = { true }) {}
        vm.openAdd()
        vm.editInput("gone.example")
        vm.find()
        idleUntil { (vm.add.value as? AddState.Editing)?.error != null }
        assertEquals(null, (vm.add.value as AddState.Editing).page)
    }

    @Test
    fun opmlImportAddsSitesAndExportWritesThem() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val input = java.io.File(ctx.cacheDir, "in.opml").apply {
            writeText("""<opml version="2.0"><body><outline text="A" xmlUrl="https://a.example/feed"/></body></opml>""")
        }
        var syncs = 0
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(FakeHttp())) { syncs++ }

        vm.importOpml(ctx.contentResolver, android.net.Uri.fromFile(input))
        idleUntil { vm.message.value != null }
        assertEquals("Added 1 site.", vm.message.value)
        assertEquals(1, syncs)

        val output = java.io.File(ctx.cacheDir, "out.opml")
        vm.dismissMessage()
        vm.exportOpml(ctx.contentResolver, android.net.Uri.fromFile(output))
        idleUntil { vm.message.value != null }
        assertEquals(true, output.readText().contains("https://a.example/feed"))
    }

    @Test
    fun aFileThatIsntOpmlSaysSo() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(FakeHttp())) {}
        vm.importOpml(ctx.contentResolver, android.net.Uri.fromFile(java.io.File(ctx.cacheDir, "missing.opml")))
        idleUntil { vm.message.value != null }
        assertEquals(true, vm.message.value!!.startsWith("Couldn't read that file"))
    }

    @Test
    fun aTtrssAccountIsTestedAddedListedAndForgotten() {
        val http = FakeHttp()
        val server = FakeTtrss(http)
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val sources = SourceRepository(db)
        var changed = 0
        val vm = SourcesViewModel(sources, FeedFinder(http), TtrssRepository(db, http, accounts, sources)) { changed++ }
        vm.rows.launchIn(kotlinx.coroutines.MainScope())

        vm.openTtrss()
        vm.editTtrss(TtrssForm(address = "rss.example.com/tt-rss", user = "reader", password = "wrong"))
        vm.connectTtrss()
        idleUntil { vm.ttrssForm.value?.error != null }
        assertEquals("tt-rss didn't accept that username and password.", vm.ttrssForm.value!!.error)

        vm.editTtrss(vm.ttrssForm.value!!.copy(password = server.password))
        vm.connectTtrss()
        idleUntil { vm.ttrssForm.value == null }
        assertEquals("a sync is asked for so the articles arrive", 1, changed)
        idleUntil { vm.rows.value?.size == 1 }
        val row = vm.rows.value!!.single()
        assertEquals(SourceKind.TTRSS, row.source.kind)

        vm.remove(row.source)
        idleUntil { vm.rows.value?.isEmpty() == true }
        idleUntil { runBlocking { accounts.load() } == StoredAccount.None }
    }
}
