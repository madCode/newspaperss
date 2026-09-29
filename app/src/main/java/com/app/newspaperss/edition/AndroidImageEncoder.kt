package com.app.newspaperss.edition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.app.newspaperss.core.images.EncodedImage
import com.app.newspaperss.core.images.ImageEncoder
import com.app.newspaperss.core.images.ImageRules
import java.io.ByteArrayOutputStream

/**
 * Re-encodes any image BitmapFactory can read (JPEG, PNG, GIF, WebP, HEIF, AVIF on newer
 * Android) as a JPEG: e-readers reliably show only JPEG, PNG and GIF, and JPEG is by far the
 * smallest for photos. Animated images keep their first frame, which is what BitmapFactory decodes.
 */
class AndroidImageEncoder : ImageEncoder {
    override fun encode(bytes: ByteArray): EncodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (width, height) = bounds.outWidth to bounds.outHeight
        if (width <= 0 || height <= 0 || !ImageRules.isWorthKeeping(width, height)) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageRules.sampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // A page can serve a photo far bigger than any the phone takes; one bad image mustn't fail the edition.
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val (targetWidth, targetHeight) = ImageRules.scaledSize(decoded.width, decoded.height)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        // JPEG has no alpha: without the white fill, transparent areas come out black.
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, null, Rect(0, 0, targetWidth, targetHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        decoded.recycle()
        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, ImageRules.JPEG_QUALITY, stream)
        output.recycle()
        return EncodedImage(stream.toByteArray(), "image/jpeg", targetWidth, targetHeight)
    }
}
