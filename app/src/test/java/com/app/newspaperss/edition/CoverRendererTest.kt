package com.app.newspaperss.edition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverRendererTest {
    private val renderer = CoverRenderer()

    private fun info(headlines: List<CoverHeadline>, title: String = "Tuesday Morning Edition") = CoverInfo(
        title = title,
        date = LocalDate.of(2026, 9, 29),
        headlines = headlines,
        articleCount = headlines.size,
        minutes = 31.4,
    )

    private val realistic = listOf(
        CoverHeadline("Scientists map the ocean floor in unprecedented detail using a fleet of autonomous drones", "Science Daily"),
        CoverHeadline("City council approves new bike lanes", "The Local Paper"),
        CoverHeadline("A long read about the history of the paperback", "Essays Weekly"),
        CoverHeadline("Why e-ink screens are still grey", "Tech Review"),
        CoverHeadline("The quiet return of the morning newspaper", "Culture Notes"),
        CoverHeadline("Seven recipes for autumn squash", "Kitchen"),
        CoverHeadline("A seventh headline that shouldn't fit", "Overflow"),
    )

    private fun decode(bytes: ByteArray): Bitmap {
        assertEquals("JPEG", 0xFF.toByte() to 0xD8.toByte(), bytes[0] to bytes[1])
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(CoverRenderer.WIDTH to CoverRenderer.HEIGHT, bitmap.width to bitmap.height)
        return bitmap
    }

    private fun Bitmap.darkPixels(top: Int, bottom: Int): Int {
        val pixels = IntArray(width * (bottom - top))
        getPixels(pixels, 0, width, 0, top, width, bottom - top)
        return pixels.count { Color.red(it) < 100 && Color.green(it) < 100 && Color.blue(it) < 100 }
    }

    @Test
    fun rendersAFullSizeJpegWithTheMastheadHeadlinesAndFooterInBlackOnWhite() {
        val image = renderer.render(info(realistic))
        assertEquals("image/jpeg", image.mediaType)
        assertEquals(CoverRenderer.HREF, image.href)
        val bitmap = decode(image.bytes)
        File("build/screenshots").apply { mkdirs() }.let { dir ->
            File(dir, "cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val corner = bitmap.getPixel(10, 10)
        assertTrue("white background", Color.red(corner) > 245 && Color.green(corner) > 245 && Color.blue(corner) > 245)
        assertTrue("masthead", bitmap.darkPixels(0, 300) > 5_000)
        assertTrue("footer", bitmap.darkPixels(bitmap.height - 200, bitmap.height) > 2_000)

        val withoutHeadlines = decode(renderer.render(info(emptyList())).bytes)
        assertTrue(
            "headlines fill the middle of the page",
            bitmap.darkPixels(700, 1400) > withoutHeadlines.darkPixels(700, 1400) + 20_000,
        )
    }

    @Test
    fun longEmojiAndRightToLeftTextAndMissingSourcesStillMakeAFullSizeCover() {
        val long = "A very long edition title that goes on and on ".repeat(20)
        val headlines = listOf(
            CoverHeadline("Breaking 🚀🔥 news with emoji 👩‍👩‍👧 and flags 🇯🇵", ""),
            CoverHeadline("أخبار الصباح من المصادر التي اخترتها بنفسك", "  "),
            CoverHeadline("חדשות הבוקר", ""),
            CoverHeadline("", "A source whose article has no title"),
            CoverHeadline(long, long),
        ) + List(40) { CoverHeadline("Headline $it", "") }

        val bitmap = decode(renderer.render(info(headlines, title = "📰 $long")).bytes)

        assertTrue("the footer is still drawn", bitmap.darkPixels(bitmap.height - 200, bitmap.height) > 2_000)
    }

    @Test
    fun theFooterReadsLikeTheBooksOwnTotals() {
        val one = info(realistic.take(1)).copy(minutes = 0.2)
        assertEquals("1 article · about 1 min", CoverRenderer.totals(one))
        assertEquals("7 articles · about 1 hr 15 min", CoverRenderer.totals(info(realistic).copy(minutes = 75.0)))
    }
}
