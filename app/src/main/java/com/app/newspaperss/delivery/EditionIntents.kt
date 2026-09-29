package com.app.newspaperss.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object EditionIntents {
    const val EPUB_MIME = "application/epub+zip"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** The share sheet, where the Kindle app appears as "Send to Kindle". */
    fun share(context: Context, file: File, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = EPUB_MIME
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send “$title” to your e-reader")
    }

    /** Opens the edition in whatever reading app handles EPUB, e.g. on a Boox. */
    fun open(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), EPUB_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
