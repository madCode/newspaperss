package com.app.newspaperss.testutil

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.app.newspaperss.delivery.EditionIntents
import org.robolectric.Shadows.shadowOf

const val MAIL_APP = "com.example.mail"

/**
 * Installs an app with one activity that handles each of [filters] (by default a mail app: it
 * writes `mailto:` and takes an EPUB), so the package manager finds it as a real phone would.
 */
fun installApp(
    context: Context,
    packageName: String = MAIL_APP,
    label: String = "Example Mail",
    filters: List<IntentFilter> = listOf(
        IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") },
        IntentFilter(Intent.ACTION_SEND).apply { addDataType(EditionIntents.EPUB_MIME) },
    ),
) {
    val pm = shadowOf(context.packageManager)
    val app = ApplicationInfo().apply { this.packageName = packageName; nonLocalizedLabel = label }
    pm.installPackage(PackageInfo().apply { this.packageName = packageName; applicationInfo = app })
    val component = ComponentName(packageName, "$packageName.Compose")
    pm.addOrUpdateActivity(
        ActivityInfo().apply {
            this.packageName = packageName
            name = component.className
            nonLocalizedLabel = label
            applicationInfo = app
            exported = true
        },
    )
    filters.forEach { pm.addIntentFilterForActivity(component, it) }
}
