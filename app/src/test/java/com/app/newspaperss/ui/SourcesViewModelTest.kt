package com.app.newspaperss.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.sources.SourcesViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesViewModelTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

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
}
