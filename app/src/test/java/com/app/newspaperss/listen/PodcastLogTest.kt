package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun itKeepsTheNewestEntriesAndDropsTheOldest() {
        val log = PodcastLog(tmp.newFile(), keep = 5)
        repeat(30) { log.add("entry $it") }
        val kept = log.read()
        assertEquals(5, kept.size)
        assertTrue(kept.last().endsWith("entry 29"))
        assertTrue(kept.first().endsWith("entry 25"))
    }

    @Test
    fun aLogThatCantBeWrittenDoesntStopTheMaking() {
        // A folder where the file should be: every write fails, as on a full disk.
        val log = PodcastLog(tmp.newFolder())
        log.add("made a piece")
        assertEquals(emptyList<String>(), log.read())
    }
}
