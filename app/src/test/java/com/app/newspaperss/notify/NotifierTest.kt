package com.app.newspaperss.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.testutil.TestApp
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
}
