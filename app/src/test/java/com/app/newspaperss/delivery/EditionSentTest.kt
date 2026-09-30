package com.app.newspaperss.delivery

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.OpenEditionActivity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionSentTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val db = app.container.db

    private fun edition(status: EditionStatus) = runBlocking {
        db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = status))
    }

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id)!!.status }

    @Test
    fun choosingAnAppInTheShareSheetMarksAReadyEditionDelivered() {
        val ready = edition(EditionStatus.READY)
        val file = File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }

        // The share sheet calls back through this sender once the reader picks an app.
        val chooser = EditionIntents.share(app, file, "Tuesday Morning Edition", ready)
        val extras = chooser.extras!!
        val callback = extras.keySet().map { extras.get(it) }.filterIsInstance<android.content.IntentSender>().single()
        callback.sendIntent(app, 0, null, null, null)

        idleUntil { statusOf(ready) == EditionStatus.DELIVERED }
        // A shared edition's notes are saved.
        idleUntil { app.notesRequested == listOf(ready) }
    }

    @Test
    fun anEditionAlreadyReleasedIsNotMarkedDeliveredBySendingItLate() {
        // Its articles went back into a newer edition; delivering this one would use them up there too.
        val released = edition(EditionStatus.FAILED)
        val ready = edition(EditionStatus.READY)

        app.sendBroadcast(Intent(app, EditionSentReceiver::class.java).putExtra(EditionSentReceiver.EXTRA_EDITION_ID, released))
        app.sendBroadcast(Intent(app, EditionSentReceiver::class.java).putExtra(EditionSentReceiver.EXTRA_EDITION_ID, ready))

        idleUntil { statusOf(ready) == EditionStatus.DELIVERED }
        assertEquals(EditionStatus.FAILED, statusOf(released))
        idleUntil { app.notesRequested == listOf(ready) }
    }

    @Test
    fun openingFromTheNotificationOpensTheEditionAndCountsAsDelivered() {
        val ready = edition(EditionStatus.READY)
        val file = File(app.filesDir, "editions/o.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        val intent = Intent(app, OpenEditionActivity::class.java)
            .putExtra(OpenEditionActivity.EXTRA_EDITION_ID, ready)
            .putExtra(OpenEditionActivity.EXTRA_FILE, file.path)

        Robolectric.buildActivity(OpenEditionActivity::class.java, intent).create()

        assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity.action)
        idleUntil { statusOf(ready) == EditionStatus.DELIVERED }
    }
}
