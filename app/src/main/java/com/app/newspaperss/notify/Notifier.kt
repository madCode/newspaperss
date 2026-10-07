package com.app.newspaperss.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.app.newspaperss.MainActivity
import com.app.newspaperss.OpenEditionActivity
import com.app.newspaperss.R
import com.app.newspaperss.SendEditionActivity
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.delivery.EditionIntents
import java.io.File

interface EditionNotifier {
    /**
     * @param openInstead the reader reads on this device, so the action opens the edition rather than sharing it.
     * @param byEmail Send emails the edition to the reader's Kindle (to the address set when it's tapped) rather than sharing it.
     */
    fun editionReady(edition: EditionEntity, file: File, openInstead: Boolean = false, byEmail: Boolean = false)
    fun editionDelivered(edition: EditionEntity, where: String)
    fun problem(title: String, reason: String)
    /** Takes down the notification about [editionId], if it's the one showing: the edition was delivered or deleted. */
    fun dismissFor(editionId: Long)
    /** A timed run found nothing new, so no edition was made. @param firstEver there's never been one. */
    fun nothingNew(firstEver: Boolean)
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
        system.createNotificationChannel(
            NotificationChannel(PODCAST, "Making the podcast", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a podcast is made, as the phone charges"
            },
        )
    }

    /**
     * Shown while a podcast is made: Android lets work run past its 10-minute limit only with a
     * notification up. Quiet, and gone when the making stops.
     */
    fun makingPodcast(): Notification = NotificationCompat.Builder(context, PODCAST)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Making the podcast")
        .setContentText("It carries on while the phone charges")
        .setContentIntent(open())
        .setOngoing(true)
        .setSilent(true)
        .build()

    override fun editionReady(edition: EditionEntity, file: File, openInstead: Boolean, byEmail: Boolean) {
        val intent = if (openInstead) {
            Intent(context, OpenEditionActivity::class.java)
                .putExtra(OpenEditionActivity.EXTRA_EDITION_ID, edition.id)
                .putExtra(OpenEditionActivity.EXTRA_FILE, file.path)
        } else if (byEmail) {
            SendEditionActivity.intent(context, edition.id, file, edition.title)
        } else {
            EditionIntents.share(context, file, edition.title, edition.id)
        }
        val action = PendingIntent.getActivity(
            context, edition.id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            NotificationCompat.Builder(context, READY)
                .addExtras(bundleOf(EXTRA_EDITION to edition.id))
                .setContentTitle("${edition.title} is ready")
                .setContentText(summary(edition))
                .addAction(0, if (openInstead) "Read" else "Send", action),
        )
    }

    override fun editionDelivered(edition: EditionEntity, where: String) = notify(
        NotificationCompat.Builder(context, EDITIONS)
            .addExtras(bundleOf(EXTRA_EDITION to edition.id))
            .setContentTitle("${edition.title} delivered")
            .setContentText("${summary(edition)} · saved to $where"),
    )

    // The slot is shared by every edition's news, so only if it's still about this one: a Send
    // left up for a deleted edition would share a file that's gone.
    override fun dismissFor(editionId: Long) {
        val showing = context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == EDITION_ID && it.notification.extras.getLong(EXTRA_EDITION, -1L) == editionId }
        if (showing) manager.cancel(EDITION_ID)
    }

    override fun nothingNew(firstEver: Boolean) = notify(
        NotificationCompat.Builder(context, EDITIONS)
            .setContentTitle("No new edition")
            .setContentText(if (firstEver) "Nothing to read yet. Add a few sources to get started." else "Nothing new to read since your last one."),
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
        // One slot per kind: a newer edition's news replaces the last one's, but
        // never an unread problem.
        manager.notify(id, builder.setSmallIcon(R.drawable.ic_notification).setContentIntent(open()).setAutoCancel(true).build())
    }

    private fun open() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun summary(edition: EditionEntity) =
        "${plural(edition.articleCount, "article")} · about ${ReadingTime.format(edition.minutes)}"

    companion object {
        // A new id, not EDITIONS made louder: Android keeps a channel's importance once created.
        const val READY = "edition-ready"
        const val EDITIONS = "editions"
        const val PROBLEMS = "problems"
        const val PODCAST = "podcast"
        private const val EXTRA_EDITION = "com.app.newspaperss.EDITION_ID"
        private const val EDITION_ID = 1
        private const val PROBLEM_ID = 2
        const val PODCAST_ID = 3
    }
}
