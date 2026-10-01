package com.app.newspaperss.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri

data class MailApp(val packageName: String, val label: String)

/**
 * Mail apps that can email an edition: they write mail (`mailto:`) and take an EPUB attachment.
 * Both, because file managers and cloud drives accept an EPUB too, and some mail apps don't
 * take attachments. Seeing them needs the manifest's `<queries>` on Android 11 and later.
 */
object MailApps {
    fun installed(context: Context): List<MailApp> {
        val pm = context.packageManager
        val writers = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { it.activityInfo.packageName }.toSet()
        return pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(EditionIntents.EPUB_MIME), 0)
            .filter { it.activityInfo.packageName in writers }
            .distinctBy { it.activityInfo.packageName }
            .map { MailApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    fun isMailApp(context: Context, packageName: String): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).setPackage(packageName), 0).isNotEmpty()
}
