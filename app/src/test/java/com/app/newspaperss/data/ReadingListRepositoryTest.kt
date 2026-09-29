package com.app.newspaperss.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val list = ReadingListRepository(db)

    @After fun close() = db.close()

    @Test
    fun savingTwiceKeepsOne() = runTest {
        assertTrue(list.save("https://a.example/1", "One"))
        assertFalse(list.save("https://a.example/1"))
        assertEquals(listOf("One"), list.observe().first().map { it.title })
    }

    @Test
    fun importedTickedItemsDontComeBackAndExportTicksWhatWasDelivered() = runTest {
        val added = list.importMarkdown("- [ ] https://a.example/1\n- [x] https://a.example/2\n- [ ] https://a.example/3\n")
        assertEquals(3, added)
        assertEquals(setOf("https://a.example/1", "https://a.example/3"), db.articles().candidates().map { it.url }.toSet())

        val one = db.articles().candidates().first { it.url == "https://a.example/1" }
        db.articles().setState(listOf(one.id), ArticleState.DELIVERED)

        assertEquals(
            "- [x] https://a.example/1\n- [x] https://a.example/2\n- [ ] https://a.example/3\n",
            list.exportMarkdown(),
        )
    }

    @Test
    fun savedLinksNeverExpireAndDontShowAsFeeds() = runTest {
        list.save("https://a.example/1")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.now().plus(Duration.ofDays(30))
        }
        FeedSync(db, FakeHttp(), clock).syncAll()
        assertEquals(1, db.articles().candidates().size)
        assertEquals(SourceKind.READING_LIST, db.sources().all().single().kind)
    }
}
