package com.app.newspaperss

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ShareActivityTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun share(text: String, subject: String? = null) {
        val intent = Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        ActivityScenario.launch<ShareActivity>(intent).close()
    }

    private fun saved() = runBlocking { app.container.db.articles().candidates() }

    @Test
    fun aSharedLinkLandsOnTheReadingListWithItsTitle() {
        share("Great piece https://a.example/story?ref=share", subject = "A great piece")
        idleUntil { saved().isNotEmpty() }
        val article = saved().single()
        assertEquals("https://a.example/story?ref=share", article.url)
        assertEquals("A great piece", article.title)
        idleUntil { ShadowToast.getTextOfLatestToast() == "Saved for your next edition" }
    }

    @Test
    fun textWithoutALinkSavesNothing() {
        share("just words")
        assertEquals("There's no link to save in that.", ShadowToast.getTextOfLatestToast())
        assertEquals(0, saved().size)
    }
}
