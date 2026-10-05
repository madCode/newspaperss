package com.app.newspaperss.delivery

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.OpenEditionActivity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun pickingTheKindleAppIsRememberedForTheNoteAndAnotherAppClearsIt() {
        val ready = edition(EditionStatus.READY)
        val file = File(app.filesDir, "editions/k.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        val extras = EditionIntents.share(app, file, "Tuesday Morning Edition", ready).extras!!
        val callback = extras.keySet().map { extras.get(it) }.filterIsInstance<android.content.IntentSender>().single()
        fun pick(packageName: String) = callback.sendIntent(
            app, 0, Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(packageName, "$packageName.Share")), null, null,
        )
        val recent = app.container.kindleSends.recent

        pick(EditionIntents.KINDLE_PACKAGE)
        idleUntil { runBlocking { recent.first() } == mapOf(ready to KindleSend.APP) }

        // Send again, this time by email: no Kindle library to wait for.
        pick("com.example.mail")
        idleUntil { runBlocking { recent.first() }.isEmpty() }
    }

    @Test
    fun theKindleNoteGoesWhenTheEditionIsMarkedNotSentAndThenSentAnotherWay() {
        File(app.filesDir, "editions/n.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        val id = runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, fileName = "n.epub")) }
        val editions = app.container.editions
        val recent = app.container.kindleSends.recent

        runBlocking { editions.markSent(id, EditionIntents.KINDLE_PACKAGE) }
        assertEquals(mapOf(id to KindleSend.APP), runBlocking { recent.first() })

        runBlocking { assertTrue(editions.markNotSent(id)) }
        assertEquals(emptyMap<Long, KindleSend>(), runBlocking { recent.first() })

        // Sent by hand (Mark as sent, or Read now on a Boox): the note mustn't come back.
        runBlocking { editions.markSent(id, EditionIntents.KINDLE_PACKAGE); editions.markNotSent(id); editions.markSent(id) }
        assertEquals(EditionStatus.DELIVERED, statusOf(id))
        assertEquals(emptyMap<Long, KindleSend>(), runBlocking { recent.first() })

        // Delivered to a folder after a Kindle send.
        runBlocking { editions.markNotSent(id); editions.markSent(id, EditionIntents.KINDLE_PACKAGE); editions.markDelivered(id) }
        assertEquals(emptyMap<Long, KindleSend>(), runBlocking { recent.first() })

        // Emailed to the Kindle, then marked as not sent, then sent by hand.
        runBlocking { editions.markNotSent(id); editions.markEmailedToKindle(id) }
        assertEquals(mapOf(id to KindleSend.EMAIL), runBlocking { recent.first() })
        runBlocking { editions.markNotSent(id) }
        assertEquals(emptyMap<Long, KindleSend>(), runBlocking { recent.first() })
        runBlocking { editions.markEmailedToKindle(id); editions.markNotSent(id); editions.markSent(id) }
        assertEquals(emptyMap<Long, KindleSend>(), runBlocking { recent.first() })
    }

    @Test
    fun sendingAnEditionTakesDownItsReadyNotification() {
        val ready = edition(EditionStatus.READY)
        val file = File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.container.notifier.createChannels()
        app.container.notifier.editionReady(runBlocking { db.editions().byId(ready)!! }, file, openInstead = false)
        val shown = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        assertEquals(1, shown.allNotifications.size)

        runBlocking { app.container.editions.markSent(ready) }

        assertEquals("its Send would offer an edition already sent", 0, shown.allNotifications.size)
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
