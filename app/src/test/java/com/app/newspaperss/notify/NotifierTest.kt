package com.app.newspaperss.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class NotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifier = Notifier(app)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels()
    }

    private fun shownText(): String {
        val shown = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        return shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    @After fun freshFileProvider() = clearFileProviderCache()

    private fun importanceOfShown(): Int {
        val system = app.getSystemService(NotificationManager::class.java)
        return system.getNotificationChannel(shadowOf(system).allNotifications.single().channelId).importance
    }

    @Test
    fun readyMakesASound() {
        clearFileProviderCache()
        val file = java.io.File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        notifier.editionReady(EditionEntity(id = 1, title = "Tuesday Morning Edition", articleCount = 7, minutes = 30.0), file)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, importanceOfShown())
    }

    @Test
    fun deliveredStaysQuiet() {
        notifier.editionDelivered(EditionEntity(title = "Tuesday Morning Edition", articleCount = 7, minutes = 30.0), "Books")
        assertEquals(NotificationManager.IMPORTANCE_LOW, importanceOfShown())
    }

    @Test
    fun aDeliveredEditionSaysHowLongItTakesToRead() {
        notifier.editionDelivered(EditionEntity(title = "Tuesday Morning Edition", articleCount = 7, minutes = 75.0), "Books")
        assertEquals("7 articles · about 1 hr 15 min · saved to Books", shownText())
    }

    @Test
    fun aOneArticleEditionIsSingularAndNeverZeroMinutes() {
        notifier.editionDelivered(EditionEntity(title = "Tuesday Morning Edition", articleCount = 1, minutes = 0.2), "Books")
        assertEquals("1 article · about 1 min · saved to Books", shownText())
    }

    @Test
    fun deletingAnEditionTakesDownItsNotificationButNotANewerOnes() {
        val shown = shadowOf(app.getSystemService(NotificationManager::class.java))
        notifier.editionDelivered(EditionEntity(id = 5, title = "Monday Morning Edition", articleCount = 7, minutes = 30.0), "Books")

        notifier.dismissFor(6)
        assertEquals(1, shown.allNotifications.size)

        notifier.dismissFor(5)
        assertEquals(0, shown.allNotifications.size)

        // The one that matters: a Send left up would share the deleted file.
        clearFileProviderCache()
        val file = java.io.File(app.filesDir, "editions/e.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        notifier.editionReady(EditionEntity(id = 7, title = "Tuesday Morning Edition", articleCount = 7, minutes = 30.0), file)
        notifier.dismissFor(7)
        assertEquals(0, shown.allNotifications.size)
    }
}
