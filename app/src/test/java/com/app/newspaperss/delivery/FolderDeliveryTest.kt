package com.app.newspaperss.delivery

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FolderDeliveryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun fileNamesAreSafeOnEveryProvider() {
        assertEquals("Tuesday Morning Edition (2).epub", FolderDelivery.fileName("Tuesday Morning Edition (2)"))
        assertEquals("Q-A- this-that.epub", FolderDelivery.fileName("Q/A: this|that"))
    }

    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val book by lazy { tmp.newFile("edition-1.epub").apply { writeBytes(byteArrayOf(1, 2, 3)) } }

    @Test
    fun theEditionIsCopiedIntoTheFolderOffTheMainThread() = runTest {
        val folder = Folder.install(tmp.newFolder("provider"))

        val error = FolderDelivery(app.contentResolver).deliver(book, Folder.TREE, "Tuesday Morning Edition.epub", EditionIntents.EPUB_MIME)

        assertNull(error)
        assertEquals(listOf(1, 2, 3), File(folder.dir, "Tuesday Morning Edition.epub").readBytes().map { it.toInt() })
        assertFalse("a cloud provider can block on the network", folder.calledOnMainThread)
    }

    @Test
    fun aFolderWeLostAccessToAsksTheReaderToPickItAgain() = runTest {
        Folder.install(tmp.newFolder("provider")).revoked = true

        val error = FolderDelivery(app.contentResolver).deliver(book, Folder.TREE, "Tuesday Morning Edition.epub", EditionIntents.EPUB_MIME)

        assertEquals("newspapeRSS no longer has access to that folder. Pick it again in Settings.", error)
    }

    /** A one-folder documents provider backed by a directory, standing in for the picked folder. */
    class Folder : DocumentsProvider() {
        lateinit var dir: File
        var calledOnMainThread = false
        /** Behaves like a folder whose grant the reader revoked or whose app was uninstalled. */
        var revoked = false

        private fun onCall() {
            if (revoked) throw SecurityException("no grant")
            if (Looper.myLooper() == Looper.getMainLooper()) calledOnMainThread = true
        }

        override fun onCreate() = true

        override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == ROOT

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf(documentId)) }

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            onCall()
            File(dir, displayName).createNewFile()
            return displayName
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            onCall()
            return ParcelFileDescriptor.open(File(dir, documentId), ParcelFileDescriptor.parseMode(mode))
        }

        companion object {
            private const val AUTHORITY = "com.app.newspaperss.test.documents"
            const val ROOT = "root"
            val TREE: String = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT).toString()

            // DocumentsProvider refuses to start without the attributes the platform requires of one.
            fun install(dir: File): Folder = Robolectric.buildContentProvider(Folder::class.java).create(
                ProviderInfo().apply {
                    authority = AUTHORITY
                    exported = true
                    grantUriPermissions = true
                    readPermission = Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = Manifest.permission.MANAGE_DOCUMENTS
                },
            ).get().also { it.dir = dir }
        }
    }
}
