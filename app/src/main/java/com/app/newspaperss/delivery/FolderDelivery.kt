package com.app.newspaperss.delivery

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

fun interface FolderWriter {
    /** Returns null on success, else a reason fit to show the reader. */
    fun deliver(file: File, treeUri: String, title: String): String?
}

/** Saves editions into a folder the reader picked through the system file picker. */
class FolderDelivery(private val resolver: ContentResolver) : FolderWriter {
    override fun deliver(file: File, treeUri: String, title: String): String? {
        var doc: Uri? = null
        return try {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            doc = DocumentsContract.createDocument(resolver, parent, EditionIntents.EPUB_MIME, fileName(title))
                ?: throw IllegalStateException("the folder refused a new file")
            resolver.openOutputStream(doc, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("couldn't write the file")
            null
        } catch (e: Exception) {
            // A half-written book would sync to the e-reader and fail to open there.
            doc?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            if (e is SecurityException) {
                "newspaperss no longer has access to that folder. Pick it again in Settings."
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
        fun fileName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(120) + ".epub"
    }
}
