package com.app.newspaperss.delivery

import android.Manifest
import android.app.Activity
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
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.DeliveryMethod
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
import org.robolectric.shadows.ShadowToast
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

        val send = EditionIntents.send(context, file, "Tuesday Morning Edition", 1L, kindle, BODY)

        assertTrue("no share sheet to report a pick, so opening it is what counts", send.countsOnLaunch)
        val email = send.intent
        assertEquals(Intent.ACTION_SEND, email.action)
        assertEquals(MAIL_APP, email.`package`)
        assertEquals(EditionIntents.EPUB_MIME, email.type)
        assertArrayEquals(arrayOf("me_42@kindle.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Tuesday Morning Edition", email.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(BODY, email.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(EditionIntents.uriFor(app, file), email.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("the mail app may attach the file after its screen has gone", listOf(MAIL_APP), granted)
    }

    @Test
    fun aMailAppThatsGoneFallsBackToTheShareSheetWithTheSameEmail() {
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, kindle, BODY)

        assertFalse(send.countsOnLaunch)
        assertEquals(Intent.ACTION_CHOOSER, send.intent.action)
        val email = send.intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertNull(email.`package`)
        assertArrayEquals("a mail app picked there still gets To filled in", arrayOf("me_42@kindle.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Tuesday Morning Edition", email.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull("the Kindle app and Dropbox are in this sheet too", email.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun aDeletedEditionHasNoEmailBody() {
        val id = readyEdition(editionFile())
        assertEquals("Tuesday Morning Edition: 0 articles, about 0 min.", runBlocking { app.container.editions.emailBody(id) })
        runBlocking { app.container.editions.delete(id) }
        assertNull("its row stays, marked deleted", runBlocking { app.container.editions.emailBody(id) })
    }

    @Test
    fun askEachTimeOffersTheShareSheet() {
        installApp(app)
        installApp(app, "com.dropbox.android", "Dropbox", listOf(IntentFilter(Intent.ACTION_SEND).apply { addDataType(EditionIntents.EPUB_MIME) }))
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, kindle.copy(mailApp = null), BODY)
        assertFalse(send.countsOnLaunch)
        assertEquals(Intent.ACTION_CHOOSER, send.intent.action)
        val perApp = send.intent.getBundleExtra(Intent.EXTRA_REPLACEMENT_EXTRAS)!!
        assertEquals("only mail apps get the body", setOf(MAIL_APP), perApp.keySet())
        assertEquals(BODY, perApp.getBundle(MAIL_APP)!!.getString(Intent.EXTRA_TEXT))
    }

    @Test
    fun withoutAKindleAddressSendSharesAsBefore() {
        val send = EditionIntents.send(context, editionFile(), "Tuesday Morning Edition", 1L, null, BODY)
        assertFalse(send.countsOnLaunch)
        val shared = send.intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertNull(shared.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertNull("every app in the share sheet would get the text, the Kindle app included", shared.getStringExtra(Intent.EXTRA_TEXT))
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

    private fun useEmail(email: KindleEmail?) = runBlocking {
        app.container.settings.update {
            it.copy(delivery = if (email != null) DeliveryMethod.KINDLE_EMAIL else DeliveryMethod.SHARE, kindleEmail = email?.address, mailApp = email?.mailApp)
        }
    }

    private fun shownNotifications() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    /** Posts the "ready" notification for email delivery and returns what its Send opens. */
    private fun notificationSend(): Intent {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.container.notifier.createChannels()
        val file = editionFile()
        val id = readyEdition(file)
        app.container.notifier.editionReady(runBlocking { db.editions().byId(id)!! }, file, byEmail = true)
        val action = shownNotifications().single().actions.single()
        assertEquals("Send", action.title)
        return shadowOf(action.actionIntent).savedIntent
    }

    private fun tapSend(intent: Intent): Pair<Activity, Intent> {
        val activity = Robolectric.buildActivity(SendEditionActivity::class.java, intent).create().get()
        idleUntil { shadowOf(app).peekNextStartedActivity() != null }
        return activity to shadowOf(app).nextStartedActivity
    }

    @Test
    fun theNotificationsSendOpensTheMailAppCountsAsSentAndComesDown() {
        installApp(app)
        useEmail(kindle)
        val intent = notificationSend()
        assertEquals("an activity of ours, which may start another from a notification", SendEditionActivity::class.java.name, intent.component?.className)
        val id = intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)
        runBlocking {
            db.editions().insertArticles(listOf(
                EditionArticleEntity(editionId = id, articleId = null, position = 0, title = "The quiet return of the night train", sourceTitle = "The Guardian", minutes = 8.4),
                EditionArticleEntity(editionId = id, articleId = null, position = 1, title = "Why bridges hum", sourceTitle = "Aeon", minutes = 5.0),
            ))
        }

        val (activity, started) = tapSend(intent)

        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals(MAIL_APP, started.`package`)
        assertArrayEquals(arrayOf("me_42@kindle.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(
            "Tuesday Morning Edition: 2 articles, about 13 min.\n\n• The quiet return of the night train — The Guardian\n• Why bridges hum — Aeon",
            started.getStringExtra(Intent.EXTRA_TEXT),
        )
        idleUntil { activity.isFinishing }
        idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        idleUntil { recent() == mapOf(id to KindleSend.EMAIL) }
        assertEquals("its Send would offer an edition already sent", 0, shownNotifications().size)
    }

    @Test
    fun theNotificationsSendUsesTheAddressAsItIsWhenTapped() {
        installApp(app)
        useEmail(kindle.copy(address = "typo@kindle.con"))
        val intent = notificationSend()

        useEmail(kindle)
        val (_, started) = tapSend(intent)

        assertArrayEquals(arrayOf("me_42@kindle.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL))
    }

    @Test
    fun switchedToSharingSinceTheNotificationItsSendSharesAndWaitsForAPick() {
        installApp(app)
        useEmail(kindle)
        val intent = notificationSend()
        val id = intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)

        useEmail(null)
        val (_, started) = tapSend(intent)

        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertNull(started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringArrayExtra(Intent.EXTRA_EMAIL))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("nothing was picked yet", EditionStatus.READY, statusOf(id))
        assertTrue(recent().isEmpty())
    }

    @Test
    fun withTheMailAppGoneTheNotificationsSendOffersTheShareSheetAndWaitsForAPick() {
        useEmail(kindle)
        val intent = notificationSend()
        val id = intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)

        val (_, started) = tapSend(intent)

        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertArrayEquals(arrayOf("me_42@kindle.com"), started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringArrayExtra(Intent.EXTRA_EMAIL))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("nothing was picked yet", EditionStatus.READY, statusOf(id))
    }

    @Test
    fun anEditionWhoseFileIsGoneIsntSent() {
        installApp(app)
        useEmail(kindle)
        val intent = notificationSend()
        File(app.filesDir, "editions/e.epub").delete()

        val activity = Robolectric.buildActivity(SendEditionActivity::class.java, intent).create().get()

        assertTrue(activity.isFinishing)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(EditionStatus.READY, statusOf(intent.getLongExtra(SendEditionActivity.EXTRA_EDITION_ID, 0L)))
    }

    @Test
    fun aMailAppThatRefusesIsReportedAndTheShareSheetOffered() {
        installApp(app)
        val started = mutableListOf<Intent>()
        val refusing = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = app
            override fun startActivity(intent: Intent) {
                if (intent.`package` == MAIL_APP) throw SecurityException("not exported")
                started += intent
            }
        }
        var opened = false

        EditionIntents.launchSend(refusing, editionFile(), "Tuesday Morning Edition", 1L, kindle) { opened = true }

        assertFalse("it didn't open, so it doesn't count as sent", opened)
        assertEquals("Couldn't open your mail app. Choose another app to send it with.", ShadowToast.getTextOfLatestToast())
        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertArrayEquals(arrayOf("me_42@kindle.com"), chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringArrayExtra(Intent.EXTRA_EMAIL))
    }

    @Test
    fun aShareSheetOpenedLaterDoesntRewriteAnEmailSheetsCallback() {
        installApp(app)
        val file = editionFile()
        val id = readyEdition(file)
        fun callbackOf(chooser: Intent) = chooser.extras!!.let { e -> e.keySet().map { e.get(it) }.filterIsInstance<IntentSender>().single() }
        val emailSheet = callbackOf(EditionIntents.send(app, file, "Tuesday Morning Edition", id, kindle.copy(mailApp = null)).intent)
        // The same edition shared the usual way, e.g. from Send again after switching delivery.
        EditionIntents.send(app, file, "Tuesday Morning Edition", id, null)

        emailSheet.sendIntent(app, 0, Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(MAIL_APP, "$MAIL_APP.Compose")), null, null)

        idleUntil { recent() == mapOf(id to KindleSend.EMAIL) }
    }
}

private const val BODY = "Tuesday Morning Edition: 1 article, about 5 min."
