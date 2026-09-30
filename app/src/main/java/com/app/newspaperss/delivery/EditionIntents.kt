package com.app.newspaperss.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.app.newspaperss.edition.EditionNotes
import java.io.File

object EditionIntents {
    const val EPUB_MIME = "application/epub+zip"

    /** @param name the file name apps are told, if not [file]'s own. */
    fun uriFor(context: Context, file: File, name: String? = null): Uri {
        val authority = "${context.packageName}.files"
        return if (name == null) FileProvider.getUriForFile(context, authority, file) else FileProvider.getUriForFile(context, authority, file, name)
    }

    /**
     * The share sheet, where the Kindle app appears as "Send to Kindle". The file is offered under
     * the edition's title, which Send to Kindle takes as the book's, whatever it's called here.
     *
     * @param editionId marks that edition sent once the reader picks an app; see [EditionSentReceiver].
     */
    fun share(context: Context, file: File, title: String, editionId: Long? = null): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = EPUB_MIME
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file, FolderDelivery.fileName(title)))
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send “$title” to your e-reader", editionId?.let { EditionSentReceiver.callback(context, it) })
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
