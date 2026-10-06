package com.app.newspaperss.listen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class KokoroManifestTest {
    @Test
    fun theAppShipsTheWholeEnglishVoice() {
        val install = KokoroInstall.of(ApplicationProvider.getApplicationContext())
        assertEquals(360, install.files.size)
        assertEquals(384_077_374L, install.bytes)
        // What the engine opens, for both accents.
        listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "espeak-ng-data/en_dict").forEach { path ->
            assertTrue(path, install.files.any { it.path == path })
        }
    }
}
