package com.app.newspaperss.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * A PNG that is transparent except for an opaque red rectangle over its middle half. Needs
 * Robolectric's native graphics (`@GraphicsMode(NATIVE)`) to produce real pixels.
 */
fun transparentPng(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.TRANSPARENT)
    Canvas(bitmap).drawRect(width / 4f, height / 4f, width * 3 / 4f, height * 3 / 4f, Paint().apply { color = Color.RED })
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
