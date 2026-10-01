package com.app.newspaperss.delivery

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.SendEditionActivity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.testutil.MAIL_APP
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Send under "Email it to your Kindle": an email to the Kindle's own address with the edition attached. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class KindleEmailTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val db = app.container.db
    private val granted = mutableListOf<String>()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = app
        override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
            granted += toPackage
        }
    }
    private val kindle = KindleEmail("me_42@kindle.com", MAIL_APP)

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun editionFile() = File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }

    private fun readyEdition(file: File) = runBlocking {
        db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, fileName = file.name))
    }

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id)!!.status }

    private fun recent() = runBlocking { app.container.kindleSends.recent.first() }

    @Test
    fun theChosenMailAppOpensDirectlyWithEverythingFilledIn() {
        installApp(app)
        val file = editionFile()

        val send = EditionIntents.send(context, file, "Tuesday Morning Edition", 1L, kindle)

        assertTrue("no share sheet to report a pick, so opening it is what counts", send.countsOnLaunch)
        val email = send.intent
        assertEquals(Intent.ACTION_SEND, email.action)
        assertEquals(MAIL_APP, email.`package`)
        assertEquals(EditionIntents.EPUB_MIME, email.type)
        assertArrayEquals(arrayOf("me_42@kindle.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Tuesday Morning Edition", email.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(EditionIntents.uriFor(app, file), email.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("the mail app may attach the file after its screen has gone", listOf(MAIL_APP), granted)
    }

    @Test
    fun aMailAppThatsGoneFallsBackToTheShareSheetWithTheSameEmail() {
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, kindle)

        assertFalse(send.countsOnLaunch)
        assertEquals(Intent.ACTION_CHOOSER, send.intent.action)
        val email = send.intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertNull(email.`package`)
        assertArrayEquals("a mail app picked there still gets To filled in", arrayOf("me_42@kindle.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Tuesday Morning Edition", email.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun askEachTimeOffersTheShareSheet() {
        installApp(app)
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, kindle.copy(mailApp = null))
        assertFalse(send.countsOnLaunch)
        assertEquals(Intent.ACTION_CHOOSER, send.intent.action)
    }

    @Test
    fun withoutAKindleAddressSendSharesAsBefore() {
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, null)
        assertFalse(send.countsOnLaunch)
        val shared = send.intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertNull(shared.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(listOf(EditionIntents.KINDLE_PACKAGE), granted)
    }

    @Test
    fun pickingAMailAppInTheFallbackShareSheetCountsAsEmailedToTheKindle() {
        installApp(app)
        installApp(app, "com.dropbox.android", "Dropbox", listOf(IntentFilter(Intent.ACTION_SEND).apply { addDataType(EditionIntents.EPUB_MIME) }))
        val file = editionFile()
        val id = readyEdition(file)
        val chooser = EditionIntents.send(app, file, "Tuesday Morning Edition", id, kindle.copy(mailApp = null)).intent
        val extras = chooser.extras!!
        val callback = extras.keySet().map { extras.get(it) }.filterIsInstance<IntentSender>().single()
        fun pick(packageName: String) = callback.sendIntent(
            app, 0, Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(packageName, "$packageName.Compose")), null, null,
        )

        pick(MAIL_APP)
        idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        idleUntil { recent() == mapOf(id to KindleSend.EMAIL) }

        // Send again, to Dropbox: that isn't an email to the Kindle.
        pick("com.dropbox.android")
        idleUntil { recent().isEmpty() }
    }

    @Test
    fun mailAppsAreThoseThatWriteMailAndTakeAnEpub() {
        installApp(app, "com.example.zmail", "zMail")
        installApp(app)
        installApp(app, "com.dropbox.android", "Dropbox", listOf(IntentFilter(Intent.ACTION_SEND).apply { addDataType(EditionIntents.EPUB_MIME) }))
        installApp(app, "com.example.textonly", "Text-only Mail", listOf(IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") }))

        assertEquals(listOf(MailApp(MAIL_APP, "Example Mail"), MailApp("com.example.zmail", "zMail")), MailApps.installed(app))
    }

    private fun notificationSend(email: KindleEmail): Intent {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.container.notifier.createChannels()
        val file = editionFile()
        val id = readyEdition(file)
        app.container.notifier.editionReady(runBlocking { db.editions().byId(id)!! }, file, kindleEmail = email)
        val shown = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val action = shown.actions.single()
        assertEquals("Send", action.title)
        return shadowOf(action.actionIntent).savedIntent
    }

    @Test
    fun theNotificationsSendOpensTheMailAppAndCountsAsSent() {
        installApp(app)
        val intent = notificationSend(kindle)
        assertEquals("an activity of ours, which may start another from a notification", SendEditionActivity::class.java.name, intent.component?.className)
        val id = intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)

        val activity = Robolectric.buildActivity(SendEditionActivity::class.java, intent).create().get()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals(MAIL_APP, started.`package`)
        assertArrayEquals(arrayOf("me_42@kindle.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertTrue(activity.isFinishing)
        idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        idleUntil { recent() == mapOf(id to KindleSend.EMAIL) }
    }

    @Test
    fun withTheMailAppGoneTheNotificationsSendOffersTheShareSheetAndWaitsForAPick() {
        val intent = notificationSend(kindle)
        val id = intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)

        Robolectric.buildActivity(SendEditionActivity::class.java, intent).create()

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("nothing was picked yet", EditionStatus.READY, statusOf(id))
    }

    @Test
    fun anEditionWhoseFileIsGoneIsntSent() {
        installApp(app)
        val intent = notificationSend(kindle)
        File(app.filesDir, "editions/e.epub").delete()

        val activity = Robolectric.buildActivity(SendEditionActivity::class.java, intent).create().get()

        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(EditionStatus.READY, statusOf(intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)))
    }
}
