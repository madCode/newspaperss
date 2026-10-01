package com.app.newspaperss

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.settings.KindleEmail
import java.io.File
import kotlinx.coroutines.launch

/**
 * The "ready" notification's Send when editions are emailed to a Kindle: opens the mail app and
 * counts that as sent. A direct launch has no share sheet to report back, and a receiver can't
 * start an activity from a notification on Android 12 and later, so the notification opens this
 * activity, which does both and closes.
 */
class SendEditionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_EDITION_ID, 0L)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)?.takeIf { it.exists() }
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (id <= 0 || file == null || address == null) {
            Toast.makeText(this, "That edition is no longer on this phone.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: file.nameWithoutExtension
        val send = EditionIntents.send(this, file, title, id, KindleEmail(address, intent.getStringExtra(EXTRA_MAIL_APP)))
        try {
            // Its own task: this one is excluded from Recents, and the half-written email shouldn't be.
            startActivity(send.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (send.countsOnLaunch) {
                val container = (application as NewspaperssApp).container
                container.appScope.launch { container.editions.markEmailedToKindle(id) }
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No mail app on this phone can send the edition.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_EDITION_ID = "editionId"
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MAIL_APP = "mailApp"

        fun intent(context: Context, editionId: Long, file: File, title: String, email: KindleEmail): Intent =
            Intent(context, SendEditionActivity::class.java)
                .putExtra(EXTRA_EDITION_ID, editionId)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ADDRESS, email.address)
                .putExtra(EXTRA_MAIL_APP, email.mailApp)
    }
}
