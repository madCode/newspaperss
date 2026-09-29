package com.app.newspaperss.delivery

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun interface FolderWriter {
    /**
     * Saves [file] into the folder as [fileName].
     *
     * @return null on success, else a reason fit to show the reader.
     */
    suspend fun deliver(file: File, treeUri: String, fileName: String, mime: String): String?
}

/** Saves editions (and their notes) into a folder the reader picked through the system file picker. */
class FolderDelivery(private val resolver: ContentResolver) : FolderWriter {
    // IO: a cloud provider's createDocument and write can block on the network.
    override suspend fun deliver(file: File, treeUri: String, fileName: String, mime: String): String? = withContext(Dispatchers.IO) {
        write(file, treeUri, fileName, mime)
    }

    private fun write(file: File, treeUri: String, fileName: String, mime: String): String? {
        var doc: Uri? = null
        return try {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            doc = DocumentsContract.createDocument(resolver, parent, mime, fileName)
                ?: throw IllegalStateException("the folder refused a new file")
            resolver.openOutputStream(doc, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("couldn't write the file")
            null
        } catch (e: Exception) {
            // A half-written book would sync to the e-reader and fail to open there.
            doc?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            if (e is SecurityException) {
                "newspapeRSS no longer has access to that folder. Pick it again in Settings."
            } else {
                "Couldn't save to the folder (${e.message})."
            }
        }
    }

    companion object {
        /** The folder's name as the provider shows it; cloud providers' document ids are opaque. */
        fun displayName(resolver: ContentResolver, treeUri: Uri): String {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            return runCatching {
                resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "your folder"
        }

        /** A file name that's valid on every provider (Drive, Dropbox, SD cards). */
        fun fileName(title: String, suffix: String = ".epub"): String =
            title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(120) + suffix
    }
}
