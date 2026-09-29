package com.app.newspaperss.edition

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.transparentPng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImageEncoderTest {
    private val encoder = AndroidImageEncoder()

    @Test
    fun transparencyBecomesWhiteInAJpeg() {
        val image = encoder.encode(transparentPng(300, 200))!!

        assertEquals("image/jpeg", image.mediaType)
        assertEquals(0xFF.toByte(), image.bytes[0])
        assertEquals(0xD8.toByte(), image.bytes[1])
        assertEquals(300 to 200, image.width to image.height)
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        val corner = decoded.getPixel(5, 5)
        assertTrue("was transparent, now white: ${Integer.toHexString(corner)}", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
        val middle = decoded.getPixel(150, 100)
        assertTrue("the opaque part keeps its colour", Color.red(middle) > 200 && Color.green(middle) < 60)
    }

    @Test
    fun largeImagesAreDownscaledToTheLongSide() {
        val image = encoder.encode(transparentPng(3000, 1000))!!
        assertEquals(1200 to 400, image.width to image.height)
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertEquals(1200 to 400, decoded.width to decoded.height)
    }

    @Test
    fun iconsAndUnreadableBytesAreRejected() {
        assertNull(encoder.encode(transparentPng(40, 400)))
        assertNull(encoder.encode("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull(encoder.encode(ByteArray(0)))
    }
}
