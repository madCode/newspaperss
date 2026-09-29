package com.app.newspaperss.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val vm = ReadingListViewModel(app.container.readingList)

    private fun import(name: String, text: String): String {
        val file = File(app.cacheDir, name).apply { writeText(text) }
        vm.import(app.contentResolver, Uri.fromFile(file))
        idleUntil { vm.message.value != null }
        return vm.message.value!!.also { vm.dismissMessage() }
    }

    @Test
    fun importSaysWhichAppTheFileCameFrom() {
        val instapaper = "URL,Title,Selection,Folder,Timestamp\nhttps://a.example/1,One,,Unread,1712345678\nhttps://a.example/2,Two,,Archive,1712345000\n"
        assertEquals("Added 2 links from Instapaper.", import("instapaper-export.csv", instapaper))
        assertEquals("Added 1 link from Pocket.", import("part_000000.csv", "title,url,time_added,tags,status\nThree,https://a.example/3,1,,unread\n"))
        assertEquals("Added 1 link.", import("list.md", "- [ ] https://a.example/4\n"))
        runBlocking { assertEquals(3, app.container.db.articles().candidates().size) }
    }
}
