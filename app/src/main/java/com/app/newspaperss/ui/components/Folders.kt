package com.app.newspaperss.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Read and write, kept across restarts: what delivering to a folder needs. */
const val FOLDER_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Keeps access to a folder the reader picked, or says why not and returns false. Some vendors'
 * pickers answer without a lasting grant, and asking for one then throws.
 */
fun keepFolderAccess(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.takePersistableUriPermission(uri, FOLDER_GRANT)
    true
} catch (_: SecurityException) {
    Toast.makeText(context, "Couldn't keep access to that folder. Try another one.", Toast.LENGTH_LONG).show()
    false
}
