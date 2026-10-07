package com.app.newspaperss.delivery

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.testutil.writeEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * An edition reaches another app's process whole.
 *
 * EditionReadGrantTest checks the flags on the intent; nothing checked that a different app can
 * open the stream they grant. That is what a reader sees as "Send didn't work", and it can't be
 * shown on the JVM: it needs a second process with its own uid, which :epubsink is.
 */
@RunWith(AndroidJUnit4::class)
class EditionReachesAnotherAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sink = "com.app.newspaperss.epubsink"

    private val articles = listOf(
        EditionArticle(
            title = "A heron on the roof",
            sourceTitle = "The Example",
            url = "https://a.example/heron",
            bodyHtml = "<p>It stood there for an hour, unbothered.</p>".repeat(40),
            minutes = 3.0,
        ),
    )

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            FileInputStream(fd.fileDescriptor).bufferedReader().readText()
        }

    @Before fun requireSink() {
        assertTrue(
            "$sink isn't installed; the device-test job installs it before this runs",
            shell("pm list packages $sink").contains(sink),
        )
        shell("logcat -c")
    }

    /** The last RESULT the sink logged, or "" if it never reported. */
    private fun sinkResult(): String {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val line = shell("logcat -d -s EpubSink").lineSequence().lastOrNull { "RESULT" in it }.orEmpty()
            if (line.isNotEmpty()) return line
            Thread.sleep(500)
        }
        return ""
    }

    @Test fun anotherAppCanReadTheWholeEditionThroughTheGrant() {
        // In the editions folder, because file_paths.xml exposes only that and notes/: the
        // FileProvider refuses anywhere else, which is the point of scoping it.
        val editions = File(context.filesDir, "editions").apply { mkdirs() }
        val epub = File(editions, "reach-test.epub").apply { writeEpub(articles) }
        try {
            // The app's own intent, with its own FileProvider uri and grant, aimed straight at the
            // sink: a test can't drive the chooser the intent is normally wrapped in.
            val chooser = EditionIntents.share(context, epub, "A heron on the roof")
            val share = chooser.getParcelableExtra(Intent.EXTRA_INTENT) ?: chooser
            share.setPackage(sink)
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(share)

            val line = sinkResult()
            assertTrue("the sink never reported: it may not have started", line.isNotEmpty())
            assertTrue("the sink couldn't read it: $line", "RESULT bytes=" in line)
            assertEquals(
                "not the whole file arrived",
                epub.length(),
                Regex("bytes=(\\d+)").find(line)!!.groupValues[1].toLong(),
            )
            assertTrue("what arrived isn't a zip, so not a whole epub: $line", "zip=true" in line)
        } finally {
            epub.delete()
        }
    }

    @Test fun anotherAppCanReadTheNotesThroughTheGrant() {
        // Notes go out as markdown from files/notes/, the other folder file_paths.xml exposes.
        val notes = File(context.filesDir, "notes").apply { mkdirs() }
        val file = File(notes, "reach-test notes.md").apply { writeText("# A heron\n\nIt stood there.\n") }
        try {
            val chooser = EditionIntents.shareNotes(context, file, "A heron on the roof")
            val share = chooser.getParcelableExtra(Intent.EXTRA_INTENT) ?: chooser
            share.setPackage(sink)
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(share)

            val line = sinkResult()
            assertTrue("the sink never reported for notes", line.isNotEmpty())
            assertTrue("the sink couldn't read the notes: $line", "RESULT bytes=" in line)
            assertEquals(
                "not the whole notes file arrived",
                file.length(),
                Regex("bytes=(\\d+)").find(line)!!.groupValues[1].toLong(),
            )
        } finally {
            file.delete()
        }
    }
}
