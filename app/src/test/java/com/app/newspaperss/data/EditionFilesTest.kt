package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionFilesTest {
    @get:Rule val dbRule = DbRule()
    @get:Rule val tmp = TemporaryFolder()
    private val db = dbRule.db

    @Test
    fun onlyTheNewestEditionsKeepTheirEpubsButAnUnsentOneKeepsItsWhateverItsAge() = runTest {
        val dir = tmp.newFolder("editions")
        val repo = EditionRepository(db, dir)
        suspend fun edition(day: Int, status: EditionStatus): Long {
            val name = "e$day.epub"
            dir.resolve(name).writeText("epub")
            return db.editions().insert(
                EditionEntity(title = "Day $day", createdAt = Instant.parse("2026-09-01T06:00:00Z").plusSeconds(86_400L * day), status = status, fileName = name),
            )
        }
        val unsent = edition(0, EditionStatus.READY)
        val oldest = edition(1, EditionStatus.DELIVERED)
        val kept = (2..4).map { edition(it, EditionStatus.DELIVERED) }

        repo.pruneFiles(keep = 3)

        assertFalse(dir.resolve("e1.epub").exists())
        assertEquals(null, db.editions().byId(oldest)!!.fileName)
        assertTrue("an unsent edition keeps its file", dir.resolve("e0.epub").exists())
        assertEquals("e0.epub", db.editions().byId(unsent)!!.fileName)
        kept.forEachIndexed { i, id -> assertEquals("e${i + 2}.epub", db.editions().byId(id)!!.fileName) }
    }
}
