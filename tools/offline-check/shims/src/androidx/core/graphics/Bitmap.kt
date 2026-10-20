// Harness shim of androidx.core.graphics (core-ktx Bitmap.kt).
@file:JvmName("ShimBitmapKt")
@file:Suppress("unused", "NOTHING_TO_INLINE")

package androidx.core.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace

inline fun createBitmap(
    width: Int,
    height: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): Bitmap = Bitmap.createBitmap(width, height, config)

inline fun createBitmap(
    width: Int,
    height: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    hasAlpha: Boolean = true,
    colorSpace: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB),
): Bitmap = Bitmap.createBitmap(width, height, config, hasAlpha, colorSpace)

inline fun Bitmap.applyCanvas(block: Canvas.() -> Unit): Bitmap {
    val c = Canvas(this)
    c.block()
    return this
}

inline operator fun Bitmap.get(
    x: Int,
    y: Int,
): Int = getPixel(x, y)

inline operator fun Bitmap.set(
    x: Int,
    y: Int,
    color: Int,
) = setPixel(x, y, color)

inline fun Bitmap.scale(
    width: Int,
    height: Int,
    filter: Boolean = true,
): Bitmap = Bitmap.createScaledBitmap(this, width, height, filter)
