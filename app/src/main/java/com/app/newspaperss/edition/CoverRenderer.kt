package com.app.newspaperss.edition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.plural
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an edition's cover shows.
 *
 * @property headlines the edition's articles in reading order; the cover shows as many of the
 *   first ones as fit.
 * @property minutes the edition's total reading time.
 */
data class CoverInfo(
    val title: String,
    val date: LocalDate,
    val headlines: List<CoverHeadline>,
    val articleCount: Int,
    val minutes: Double,
)

data class CoverHeadline(val title: String, val source: String)

/**
 * Draws an edition's cover as a JPEG, so the e-reader's library shows the paper's name, date and
 * first headlines instead of a generic placeholder.
 *
 * Pure black on white and large type: e-ink screens are grayscale, and the library shows the
 * cover as a thumbnail a few centimetres tall.
 */
class CoverRenderer {
    fun render(info: CoverInfo): EpubImage {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        try {
            draw(Canvas(bitmap), info)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            return EpubImage(HREF, "image/jpeg", stream.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    // Top to bottom: masthead between rules, date, edition title, then headlines until the space
    // above the footer runs out, then the totals footer pinned to the bottom.
    private fun draw(canvas: Canvas, info: CoverInfo) {
        canvas.drawColor(Color.WHITE)
        val width = WIDTH - 2 * MARGIN
        var y = MARGIN.toFloat()

        y = drawText(canvas, "newspaperss", paint(136f, SERIF_BOLD), width, y, Layout.Alignment.ALIGN_CENTER, 1)
        y += 24f
        y = rule(canvas, y, 10f) + 10f
        y = rule(canvas, y, 3f) + 30f
        y = drawText(canvas, DATE.format(info.date), paint(46f, SERIF), width, y, Layout.Alignment.ALIGN_CENTER, 1) + 16f
        y = drawText(canvas, info.title, paint(76f, SERIF_BOLD), width, y, Layout.Alignment.ALIGN_CENTER, 2) + 32f
        y = rule(canvas, y, 3f) + 40f

        val footer = paint(52f, SERIF)
        val footerTop = HEIGHT - MARGIN - footer.fontSpacing - 36f
        val headlineBottom = footerTop - 30f
        val sourcePaint = paint(34f, SANS)
        var shown = 0
        for (headline in info.headlines.filter { it.title.isNotBlank() }) {
            if (shown == MAX_HEADLINES) break
            // The lead story gets more room; the rest are smaller so four to six fit.
            val lead = shown == 0
            val title = layout(headline.title.trim(), paint(if (lead) 60f else 48f, SERIF_BOLD), width, Layout.Alignment.ALIGN_NORMAL, if (lead) 3 else 2)
            val source = headline.source.trim().takeIf { it.isNotEmpty() }
                ?.let { layout(it.uppercase(Locale.ROOT), sourcePaint, width, Layout.Alignment.ALIGN_NORMAL, 1) }
            val height = title.height + (source?.let { it.height + 8f } ?: 0f)
            if (y + height > headlineBottom) break
            if (shown > 0) rule(canvas, y - 20f, 2f)
            y = drawLayout(canvas, title, y)
            if (source != null) y = drawLayout(canvas, source, y + 8f)
            y += 40f
            shown++
        }

        rule(canvas, footerTop, 3f)
        drawText(canvas, totals(info), footer, width, footerTop + 30f, Layout.Alignment.ALIGN_CENTER, 1)
    }

    private fun paint(size: Float, typeface: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        this.typeface = typeface
    }

    private fun layout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    /** Draws [layout] with its top at [top] and returns its bottom. */
    private fun drawLayout(canvas: Canvas, layout: StaticLayout, top: Float): Float {
        canvas.withTranslation(MARGIN.toFloat(), top) { layout.draw(this) }
        return top + layout.height
    }

    private fun drawText(canvas: Canvas, text: String, paint: TextPaint, width: Int, top: Float, alignment: Layout.Alignment, maxLines: Int) =
        drawLayout(canvas, layout(text, paint, width, alignment, maxLines), top)

    /** Draws a full-width rule of [thickness] with its top at [top] and returns its bottom. */
    private fun rule(canvas: Canvas, top: Float, thickness: Float): Float {
        canvas.drawRect(MARGIN.toFloat(), top, (WIDTH - MARGIN).toFloat(), top + thickness, Paint().apply { color = Color.BLACK })
        return top + thickness
    }

    companion object {
        /** The cover's footer line. */
        internal fun totals(info: CoverInfo) = "${plural(info.articleCount, "article")} · about ${ReadingTime.format(info.minutes)}"

        /** A portrait e-reader screen (Kobo/Paperwhite class), as rss-to-e-reader uses; not KDP's 1600x2560. */
        const val WIDTH = 1264
        const val HEIGHT = 1680
        const val HREF = "images/cover.jpg"
        private const val QUALITY = 85
        private const val MARGIN = 90
        private const val MAX_HEADLINES = 6
        private val SERIF = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        private val SERIF_BOLD = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        private val SANS = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        // English like the rest of the book's own text.
        private val DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
    }
}
