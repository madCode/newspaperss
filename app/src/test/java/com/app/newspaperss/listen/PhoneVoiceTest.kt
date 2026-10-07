package com.app.newspaperss.listen

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.installVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PhoneVoiceTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun itNamesTheEngineChosenInAndroidsSettings() {
        assertNull(PhoneVoice.name(context))
        installVoice(context, "Speech Services by Google", "com.example.google")
        installVoice(context, "RHVoice", "com.example.rhvoice")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.example.rhvoice")
        assertEquals("RHVoice", PhoneVoice.name(context))
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.example.google")
        assertEquals("Speech Services by Google", PhoneVoice.name(context))
    }
}
