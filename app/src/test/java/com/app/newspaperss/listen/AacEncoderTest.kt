package com.app.newspaperss.listen

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import com.app.newspaperss.testutil.TestApp
import java.io.ByteArrayOutputStream

/** The encoder's plumbing, against Robolectric's stand-in codec: what it's fed, and that it finishes. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class AacEncoderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fed = ByteArrayOutputStream()

    @After fun clear() = ShadowMediaCodec.clearCodecs()

    private fun codec() = ShadowMediaCodec.addEncoder(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        ShadowMediaCodec.CodecConfig(4096, 4096) { input, output ->
            val bytes = ByteArray(input.remaining()).also { input.get(it) }
            fed.write(bytes)
            output.put(bytes)
        },
    )

    @Test
    fun speechGoesInAsSixteenBitSamplesInThePhonesByteOrderAndTheFileIsFinished() {
        codec()
        val file = tmp.newFile("page.m4a")
        // More than one input buffer's worth, so it's fed in pieces.
        AacEncoder.open(file, 24_000).use { sink ->
            sink.write(FloatArray(3_000) { 0.5f })
            sink.write(floatArrayOf(2f, -2f))
        }

        val bytes = fed.toByteArray()
        assertEquals(3_002 * 2, bytes.size)
        // 0.5 is 16383, low byte first as on every Android phone.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x3F.toByte(), bytes[1])
        // Anything louder than full scale is held there, not wrapped round.
        assertEquals(Short.MAX_VALUE, short(bytes, 3_000))
        assertEquals((-Short.MAX_VALUE).toShort(), short(bytes, 3_001))
        assertTrue(file.exists())
    }

    @Test
    fun closingTwiceIsHarmless() {
        codec()
        val sink = AacEncoder.open(tmp.newFile("page.m4a"), 24_000)
        sink.write(FloatArray(10))
        sink.close()
        sink.close()
    }

    private fun short(bytes: ByteArray, sample: Int): Short =
        ((bytes[sample * 2].toInt() and 0xFF) or (bytes[sample * 2 + 1].toInt() shl 8)).toShort()
}
