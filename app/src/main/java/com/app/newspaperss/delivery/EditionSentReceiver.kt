package com.app.newspaperss.delivery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.IntentCompat
import com.app.newspaperss.NewspaperssApp
import kotlinx.coroutines.launch

/**
 * Told by the share sheet which app the reader picked for an edition. Picking one (Send to
 * Kindle, Dropbox, email) is the closest the app can get to knowing it was sent, and it works
 * from the notification too, where nothing else would record the send.
 */
class EditionSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chosen = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        val file = IntentCompat.getParcelableExtra(intent, EXTRA_FILE, Uri::class.java)
        if (chosen != null && file != null) EditionIntents.grantRead(context, chosen.packageName, file)
        val id = intent.getLongExtra(EXTRA_EDITION_ID, 0L).takeIf { it > 0 } ?: return
        val app = context.applicationContext as NewspaperssApp
        val pending = goAsync()
        // Picked from an email to the Kindle: a mail app sends it there, anything else is another route.
        val emailed = intent.getBooleanExtra(EXTRA_KINDLE_EMAIL, false) && chosen != null && MailApps.isMailApp(context, chosen.packageName)
        app.container.appScope.launch {
            try {
                if (emailed) app.container.editions.markEmailedToKindle(id) else app.container.editions.markSent(id, chosen?.packageName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EDITION_ID = "editionId"
        const val EXTRA_FILE = "file"
        const val EXTRA_KINDLE_EMAIL = "kindleEmail"

        /**
         * Mutable so the share sheet can fill in the app that was picked, which is then allowed to
         * read [file] after its screen closes (see [EditionIntents.grantRead]).
         *
         * @param editionId the edition to mark sent, or null to only grant the read.
         * @param kindleEmail the share sheet offers an email to the reader's Kindle address.
         */
        fun callback(context: Context, editionId: Long?, file: Uri, kindleEmail: Boolean = false): IntentSender {
            val intent = Intent(context, EditionSentReceiver::class.java).putExtra(EXTRA_FILE, file).putExtra(EXTRA_KINDLE_EMAIL, kindleEmail)
            editionId?.let { intent.putExtra(EXTRA_EDITION_ID, it) }
            return PendingIntent.getBroadcast(
                context,
                // Extras don't tell PendingIntents apart, so the code does: per edition (or, without
                // one, file) and per kind, or a later sheet's callback would rewrite an open one's.
                ((editionId?.toInt() ?: file.hashCode()) shl 1) or (if (kindleEmail) 1 else 0),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ).intentSender
        }
    }
}
