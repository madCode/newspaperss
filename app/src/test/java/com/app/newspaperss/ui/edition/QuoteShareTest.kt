package com.app.newspaperss.ui.edition

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class QuoteShareTest {
    private val link = ArticleLink("A story", "https://a.example/story")

    private fun text(vararg extras: Pair<String, String>) = Intent(Intent.ACTION_SEND).setType("text/plain").apply { extras.forEach { (k, v) -> putExtra(k, v) } }

    @Test
    fun aSharedQuoteGetsTheAddressOnItsOwnLineAndTheTitleAsSubject() {
        val out = withSource(text(Intent.EXTRA_TEXT to "A quote. "), link)
        assertEquals("A quote.\n\nhttps://a.example/story", out.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("A story", out.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun aSubjectAlreadyThereIsKept() {
        val out = withSource(text(Intent.EXTRA_TEXT to "A quote", Intent.EXTRA_SUBJECT to "Theirs"), link)
        assertEquals("Theirs", out.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun textThatAlreadyHasTheAddressIsLeftAlone() {
        val out = withSource(text(Intent.EXTRA_TEXT to "https://a.example/story"), link)
        assertEquals("https://a.example/story", out.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun anythingButSharingTextGoesOutUnchanged() {
        val browse = Intent(Intent.ACTION_VIEW, Uri.parse("https://b.example/"))
        assertSame(browse, withSource(browse, link))
        assertEquals(null, withSource(browse, link).getStringExtra(Intent.EXTRA_TEXT))
        val noLink = withSource(text(Intent.EXTRA_TEXT to "A quote"), null)
        assertEquals("A quote", noLink.getStringExtra(Intent.EXTRA_TEXT))
    }
}
