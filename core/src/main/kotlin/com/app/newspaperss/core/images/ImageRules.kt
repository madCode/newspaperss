package com.app.newspaperss.core.images

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image decoded, resized and re-encoded for an e-reader.
 *
 * @property mediaType `image/jpeg`, `image/png` or `image/gif`.
 * @property width the encoded image's width in pixels.
 * @property height the encoded image's height in pixels.
 */
class EncodedImage(
    val bytes: ByteArray,
    val mediaType: String,
    val width: Int,
    val height: Int,
)

/**
 * Turns downloaded image bytes into an image e-readers show well. Decoding needs the platform
 * (Android's BitmapFactory), so :core only defines the contract; see [ImageRules] for the sizes.
 */
fun interface ImageEncoder {
    /**
     * [bytes] as an [EncodedImage] no larger than [ImageRules.MAX_DIMENSION] on its long side,
     * or null if they aren't a readable image or it is smaller than [ImageRules.MIN_DIMENSION]
     * on either side.
     */
    fun encode(bytes: ByteArray): EncodedImage?
}

/**
 * Sizes and limits for images in an edition. E-ink screens are about 1264x1680 at most and
 * Send to Kindle (and email in general) limits a document's size, so images are downscaled,
 * re-encoded as JPEG and budgeted.
 */
object ImageRules {
    const val MAX_DIMENSION = 1200

    /** Smaller images are icons, avatars and spacers. */
    const val MIN_DIMENSION = 48
    const val JPEG_QUALITY = 80
    const val MAX_PER_ARTICLE = 20
    const val MAX_EDITION_BYTES = 15L * 1024 * 1024

    fun isWorthKeeping(width: Int, height: Int) = min(width, height) >= MIN_DIMENSION

    /**
     * The largest power-of-two subsampling that still decodes at least [maxDimension] pixels on
     * the long side, so a huge photo is never decoded at full size but the final downscale still
     * has enough pixels to look sharp.
     */
    fun sampleSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        val long = max(width, height)
        var sample = 1
        while (long / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /** [width] x [height] scaled down, keeping the aspect ratio, to fit [maxDimension]; never scaled up. */
    fun scaledSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
