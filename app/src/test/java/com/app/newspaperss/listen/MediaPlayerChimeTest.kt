package com.app.newspaperss.listen

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MediaPlayerChimeTest {
    private val chime = MediaPlayerChime(ApplicationProvider.getApplicationContext())
    private var done = 0

    @After fun reset() = ShadowMediaPlayer.resetStaticState()

    private fun playable(ms: Int) = ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(ms, 0) }

    private fun after(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun itPlaysToTheEndAndThenSaysSoOnce() {
        playable(4_000)
        chime.play { done++ }
        after(1_000)
        assertEquals("not before it's heard", 0, done)
        after(10_000)
        assertEquals("once, the fallback notwithstanding", 1, done)
    }

    @Test
    fun stoppedItNeverSaysItsDone() {
        playable(4_000)
        chime.play { done++ }
        after(1_000)
        chime.stop()
        after(10_000)
        assertEquals(0, done)
    }

    @Test
    fun aChimeThatCantPlayIsPassedOver() {
        // No media info: the player fails to prepare, and Listen mustn't wait on it.
        chime.play { done++ }
        after(100)
        assertEquals(1, done)
    }

    @Test
    fun aPlayerThatNeverFinishesIsGivenUpOn() {
        // Far longer than the chime: as if its completion never came.
        playable(60_000)
        chime.play { done++ }
        after(5_000)
        assertEquals(0, done)
        after(2_000)
        assertEquals(1, done)
    }
}
