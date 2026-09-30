package com.app.newspaperss.delivery

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionShareNameTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun editionFile(name: String) = File(app.filesDir, "editions/$name").apply { parentFile!!.mkdirs(); writeText("epub") }

    private fun nameAndSize(uri: Uri) = app.contentResolver.query(uri, null, null, null, null)!!.use {
        it.moveToFirst()
        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
    }

    @Test
    fun anEditionIsSharedUnderItsTitleWhateverItsFileIsCalled() {
        // Send to Kindle titles the book after this name, and an edition's file may be edition-<id>.epub.
        val chooser = EditionIntents.share(app, editionFile("edition-3.epub"), "Wednesday Morning Edition, Sep 30")
        val stream = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            .getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!

        assertEquals("Wednesday Morning Edition, Sep 30.epub" to 4L, nameAndSize(stream))
        assertEquals("epub", app.contentResolver.openInputStream(stream)!!.use { it.reader().readText() })
        assertEquals(EditionIntents.EPUB_MIME, app.contentResolver.getType(stream))
    }

    @Test
    fun aFileSharedWithoutANameKeepsItsOwn() {
        assertEquals("notes.md" to 4L, nameAndSize(EditionIntents.uriFor(app, editionFile("notes.md"))))
    }
}
