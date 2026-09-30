package com.app.newspaperss.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.app.newspaperss.MainActivity
import com.app.newspaperss.OpenEditionActivity
import com.app.newspaperss.R
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.delivery.EditionIntents
import java.io.File

interface EditionNotifier {
    /** @param openInstead the reader reads on this device, so the action opens the edition rather than sharing it. */
    fun editionReady(edition: EditionEntity, file: File, openInstead: Boolean = false)
    fun editionDelivered(edition: EditionEntity, where: String)
    fun problem(title: String, reason: String)
    /** A timed run found nothing new, so no edition was made. */
    fun nothingNew()
}

/**
 * Failures are loud and successes quiet, but "ready" makes a sound: for share delivery it's the
 * reader's only prompt to send the edition.
 */
class Notifier(private val context: Context) : EditionNotifier {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(READY, "Edition ready", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your edition is ready to send or open"
            },
        )
        system.createNotificationChannel(
            NotificationChannel(EDITIONS, "Edition delivered", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Your edition was saved to your folder"
            },
        )
        system.createNotificationChannel(
            NotificationChannel(PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An edition couldn't be made or delivered"
            },
        )
    }

    override fun editionReady(edition: EditionEntity, file: File, openInstead: Boolean) {
        val intent = if (openInstead) {
            Intent(context, OpenEditionActivity::class.java)
                .putExtra(OpenEditionActivity.EXTRA_EDITION_ID, edition.id)
                .putExtra(OpenEditionActivity.EXTRA_FILE, file.path)
        } else {
            EditionIntents.share(context, file, edition.title, edition.id)
        }
        val action = PendingIntent.getActivity(
            context, edition.id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            NotificationCompat.Builder(context, READY)
                .setContentTitle("${edition.title} is ready")
                .setContentText(summary(edition))
                .addAction(0, if (openInstead) "Open" else "Send", action),
        )
    }

    override fun editionDelivered(edition: EditionEntity, where: String) = notify(
        NotificationCompat.Builder(context, EDITIONS)
            .setContentTitle("${edition.title} delivered")
            .setContentText("${summary(edition)} · saved to $where"),
    )

    override fun nothingNew() = notify(
        NotificationCompat.Builder(context, EDITIONS)
            .setContentTitle("No edition today")
            .setContentText("Nothing new to read since your last one."),
    )

    override fun problem(title: String, reason: String) = notify(
        NotificationCompat.Builder(context, PROBLEMS)
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH),
        id = PROBLEM_ID,
    )

    private fun notify(builder: NotificationCompat.Builder, id: Int = EDITION_ID) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // One slot per kind: a newer edition's news replaces the last one's, but
        // never an unread problem.
        manager.notify(id, builder.setSmallIcon(R.drawable.ic_notification).setContentIntent(open).setAutoCancel(true).build())
    }

    private fun summary(edition: EditionEntity) =
        "${plural(edition.articleCount, "article")} · about ${ReadingTime.format(edition.minutes)}"

    companion object {
        // A new id, not EDITIONS made louder: Android keeps a channel's importance once created.
        const val READY = "edition-ready"
        const val EDITIONS = "editions"
        const val PROBLEMS = "problems"
        private const val EDITION_ID = 1
        private const val PROBLEM_ID = 2
    }
}
