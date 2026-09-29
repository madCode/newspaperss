package com.app.newspaperss.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.newspaperss.NewspaperssApp
import kotlinx.coroutines.launch

/**
 * Re-arms the edition timer after a time-zone or clock change. Its delay was a fixed duration
 * computed in the old zone, so after a flight "6:30" would otherwise arrive at the wrong hour.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val app = context.applicationContext as NewspaperssApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                EditionScheduler.reschedule(app, app.container.settings.current())
            } finally {
                pending.finish()
            }
        }
    }
}
