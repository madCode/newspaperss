package com.app.newspaperss.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.app.newspaperss.edition.EditionNotes
import java.io.File

object EditionIntents {
    const val EPUB_MIME = "application/epub+zip"
    const val KINDLE_PACKAGE = "com.amazon.kindle"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * The share sheet, where the Kindle app appears as "Send to Kindle".
     *
     * @param editionId marks that edition sent once the reader picks an app; see [EditionSentReceiver].
     */
    fun share(context: Context, file: File, title: String, editionId: Long? = null): Intent {
        val uri = uriFor(context, file)
        // Granted ahead of the pick as well: the Kindle app can lose the share's own grant before
        // reading the book (see [grantRead]), and the pick's callback may come too late.
        grantRead(context, KINDLE_PACKAGE, uri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = EPUB_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send “$title” to your e-reader", EditionSentReceiver.callback(context, editionId, uri))
    }

    /**
     * Lets [packageName] read [uri] until the phone restarts. A share's own grant lasts only while
     * the receiving screen is open, and Send to Kindle uploads after its form closes, so the upload
     * found the book unreadable and failed without a word. Does nothing if the app isn't installed.
     */
    fun grantRead(context: Context, packageName: String, uri: Uri) {
        try {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    /** The share sheet for an edition's reading notes, e.g. to a notes app or Files. */
    fun shareNotes(context: Context, file: File, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = EditionNotes.MIME
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, "$title notes")
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Save notes for “$title”")
    }

    /** Opens the edition in whatever reading app handles EPUB, e.g. on a Boox. */
    fun open(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), EPUB_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
