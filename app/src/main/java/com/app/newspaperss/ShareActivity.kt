package com.app.newspaperss

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.app.newspaperss.core.feed.MarkdownChecklist
import kotlinx.coroutines.launch

/** The share-sheet target: saves the shared link to the reading list without opening the app. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val url = MarkdownChecklist.firstUrl(text)
        if (url == null) {
            Toast.makeText(this, "There's no link to save in that.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The subject is usually the page title; some apps put the URL there instead.
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeUnless { it.contains("://") }
        val container = (application as NewspaperssApp).container
        val appContext = applicationContext
        container.appScope.launch {
            val saved = container.readingList.save(url, title)
            Toast.makeText(appContext, if (saved) "Saved for your next edition" else "Already on your reading list", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
