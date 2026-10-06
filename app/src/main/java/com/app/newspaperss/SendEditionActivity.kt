package com.app.newspaperss

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.app.newspaperss.delivery.EditionIntents
import java.io.File
import kotlinx.coroutines.launch

/**
 * The "ready" notification's Send when editions are emailed to a Kindle: opens the mail app and
 * counts that as sent. A direct launch has no share sheet to report back, and a receiver can't
 * start an activity from a notification on Android 12 and later, so the notification opens this
 * activity, which does both and closes. It reads the settings when tapped, not when the
 * notification was posted: the address or delivery may have changed since.
 */
class SendEditionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_EDITION_ID, 0L)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)?.takeIf { it.exists() }
        if (id <= 0 || file == null) {
            Toast.makeText(this, "That edition is no longer on this phone.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Recreated (a rotation while it reads the settings): the first one is already sending.
        if (savedInstanceState != null) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: file.nameWithoutExtension
        val container = application.container
        container.appScope.launch {
            try {
                val email = container.settings.current().kindleEmailTarget
                val body = email?.let { container.editions.emailBody(id) }
                // Its own task: this one is excluded from Recents, and the half-written email shouldn't be.
                EditionIntents.launchSend(this@SendEditionActivity, file, title, id, email, body, newTask = true) {
                    container.appScope.launch { container.editions.markEmailedToKindle(id) }
                }
            } finally {
                // Whatever happened: left open, this see-through screen would sit over everything.
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_EDITION_ID = "editionId"
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"

        fun intent(context: Context, editionId: Long, file: File, title: String): Intent =
            Intent(context, SendEditionActivity::class.java)
                .putExtra(EXTRA_EDITION_ID, editionId)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_TITLE, title)
    }
}
