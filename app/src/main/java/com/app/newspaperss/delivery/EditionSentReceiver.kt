package com.app.newspaperss.delivery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.app.newspaperss.NewspaperssApp
import kotlinx.coroutines.launch

/**
 * Told by the share sheet which app the reader picked for an edition. Picking one (Send to
 * Kindle, Dropbox, email) is the closest the app can get to knowing it was sent, and it works
 * from the notification too, where nothing else would record the send.
 */
class EditionSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EDITION_ID, 0L).takeIf { it > 0 } ?: return
        val app = context.applicationContext as NewspaperssApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.editions.markSent(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EDITION_ID = "editionId"

        // Immutable: only the broadcast matters, not the chosen component the system would fill in.
        fun callback(context: Context, editionId: Long): IntentSender = PendingIntent.getBroadcast(
            context,
            editionId.toInt(),
            Intent(context, EditionSentReceiver::class.java).putExtra(EXTRA_EDITION_ID, editionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).intentSender
    }
}
