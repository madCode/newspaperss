package com.app.newspaperss.listen

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ListenServiceTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val player get() = app.container.listen

    /** Starts the container's player on a one-article edition. */
    private fun play() = runBlocking {
        val dir = File(app.filesDir, "editions").apply { mkdirs() }
        val article = EditionArticle("Knots", "Quanta", "https://example.com/k", "<p>One. Two. Three.</p>", 1.0)
        File(dir, "e.epub").outputStream().use {
            EpubWriter.write(EditionDoc("Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000009", listOf(EditionSection(null, listOf(article)))), it)
        }
        val id = app.container.db.editions().insert(EditionEntity(title = "Edition", status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = 1, minutes = 1.0))
        app.container.db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = null, position = 0, title = "Knots", sourceTitle = "Quanta", minutes = 1.0)))
        player.start(id)
        idleUntil { player.state.value.script != null }
    }

    @Test
    fun aCallPausesItAndItCarriesOnAfterwardsButAnotherAppTakingOverStopsIt() {
        val service = Robolectric.buildService(ListenService::class.java).create()
        assertNotNull(service.get().onGetSession(MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.example", 0, 0, 0, 0, false, Bundle(), false)))
        play()
        val focus = shadowOf(audio).lastAudioFocusRequest
        assertNotNull(focus)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(player.state.value.playing)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(player.state.value.playing)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(player.state.value.playing)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(player.state.value.playing)
        service.destroy()
    }

    @Test
    fun unpluggingHeadphonesPausesIt() {
        val service = Robolectric.buildService(ListenService::class.java).create()
        play()
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.state.value.playing)
        // Swiped away while paused: the service goes.
        service.get().onTaskRemoved(null)
        service.destroy()
    }
}
