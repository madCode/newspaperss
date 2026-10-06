package com.app.newspaperss

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import com.app.newspaperss.delivery.EditionIntents
import java.io.File
import kotlinx.coroutines.launch

/**
 * The "ready" notification's Open, for readers who read on this device (a Boox): opens the
 * edition and counts that as delivered, which a plain view intent from the notification can't.
 */
class OpenEditionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_EDITION_ID, 0L)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)?.takeIf { it.exists() }
        if (id <= 0 || file == null) {
            Toast.makeText(this, "That edition is no longer on this phone.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            // Its own task: this one is excluded from Recents, and the book shouldn't be.
            startActivity(EditionIntents.open(this, file).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val container = application.container
            container.appScope.launch { container.editions.markSent(id) }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No reading app on this phone can open the edition.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_EDITION_ID = "editionId"
        const val EXTRA_FILE = "file"
    }
}
