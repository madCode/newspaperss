package com.app.newspaperss.delivery

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Send to Kindle uploads after its form closes, when a share's own read grant has ended, so the
 * app it goes to is granted a read that outlasts the screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionReadGrantTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val granted = mutableListOf<Pair<String, Uri>>()

    /** Records grants; everything else goes to the real app. */
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = app
        override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
            granted += toPackage to uri
        }
    }

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun editionFile() = File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }

    private fun streamOf(chooser: Intent) = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!

    @Test
    fun theKindleAppMayReadTheEditionBeforeAnythingIsPicked() {
        val chooser = EditionIntents.share(context, editionFile(), "Wednesday Morning Edition")
        assertEquals(listOf(EditionIntents.KINDLE_PACKAGE to streamOf(chooser)), granted)
    }

    @Test
    fun theAppPickedInTheShareSheetMayReadTheEditionAfterItsScreenCloses() {
        val chooser = EditionIntents.share(context, editionFile(), "Wednesday Morning Edition")
        granted.clear()
        val extras = chooser.extras!!
        val callback = extras.keySet().map { extras.get(it) }.filterIsInstance<IntentSender>().single()
        val picked = ComponentName("com.dropbox.android", "com.dropbox.android.Share")

        // What the share sheet sends once the reader picks an app.
        callback.sendIntent(app, 0, Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, picked), null, null)
        val broadcast = org.robolectric.Shadows.shadowOf(app).broadcastIntents.last()
        EditionSentReceiver().onReceive(context, broadcast)

        assertEquals(listOf("com.dropbox.android" to streamOf(chooser)), granted)
    }

    @Test
    fun anAppThatIsntInstalledIsSkipped() {
        EditionIntents.grantRead(app, "com.example.not.installed", EditionIntents.uriFor(app, editionFile()))
    }
}
