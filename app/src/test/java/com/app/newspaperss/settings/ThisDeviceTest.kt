package com.app.newspaperss.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ThisDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun on(manufacturer: String, calls: Boolean): Boolean {
        ShadowBuild.setManufacturer(manufacturer)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, calls)
        return ThisDevice.isEReader(app)
    }

    @Test
    fun aBooxIsAnEReader() = assertTrue(on("ONYX", calls = false))

    @Test
    fun aPhoneIsNot() = assertFalse(on("Google", calls = true))

    @Test
    fun anyDeviceThatCantMakeCallsIs() = assertTrue(on("Example", calls = false))

    @Test
    fun anEInkPhoneFromAnEInkMakerIs() {
        assertTrue(on("Bigme", calls = true))
        assertTrue(on("Mudita", calls = true))
    }

    @Test
    fun readingHereNeedsBothABooxReaderAndTheEReader() {
        assertTrue(Device.BOOX.readsHere(onEReader = true))
        assertFalse(Device.BOOX.readsHere(onEReader = false))
        assertFalse(Device.KOBO.readsHere(onEReader = true))
        assertFalse((null as Device?).readsHere(onEReader = true))
    }
}
