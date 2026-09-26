package com.example.russianplatescanner.util

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.max

private const val WIDTH_FRACTION = 0.82f
private const val HEIGHT_FRACTION = 0.22f

object GuideCrop {
    fun of(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth < 8 || imageHeight < 8) return bitmap
        val bounds = guideBounds(imageWidth, imageHeight, viewWidth, viewHeight)
        val left = bounds[0].coerceIn(0, imageWidth - 1)
        val top = bounds[1].coerceIn(0, imageHeight - 1)
        val width = bounds[2].coerceIn(1, imageWidth - left)
        val height = bounds[3].coerceIn(1, imageHeight - top)
        if (width >= imageWidth - 2 && height >= imageHeight - 2) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}

internal fun ImageProxy.toGuideBitmap(viewWidth: Int, viewHeight: Int): Bitmap {
    val rotation = imageInfo.rotationDegrees
    val uprightWidth = if (rotation == 90 || rotation == 270) height else width
    val uprightHeight = if (rotation == 90 || rotation == 270) width else height
    val bounds = guideBounds(uprightWidth, uprightHeight, viewWidth, viewHeight)
    val cropWidth = bounds[2].coerceAtLeast(1)
    val cropHeight = bounds[3].coerceAtLeast(1)
    val yPlane = planes[0]
    val buffer = yPlane.buffer
    buffer.rewind()
    val luma = ByteArray(buffer.remaining())
    buffer.get(luma)
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride.coerceAtLeast(1)
    val pixels = IntArray(cropWidth * cropHeight)
    var index = 0
    for (y in 0 until cropHeight) {
        val uprightY = bounds[1] + y
        for (x in 0 until cropWidth) {
            val uprightX = bounds[0] + x
            val sensor = uprightToSensor(uprightX, uprightY, rotation, width, height)
            val offset = sensor[1] * rowStride + sensor[0] * pixelStride
            val gray = if (offset in luma.indices) luma[offset].toInt() and 0xFF else 0
            pixels[index++] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    }
    return Bitmap.createBitmap(pixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
}

private fun uprightToSensor(x: Int, y: Int, rotation: Int, width: Int, height: Int): IntArray {
    val point = when (rotation) {
        90 -> intArrayOf(y, height - 1 - x)
        180 -> intArrayOf(width - 1 - x, height - 1 - y)
        270 -> intArrayOf(width - 1 - y, x)
        else -> intArrayOf(x, y)
    }
    point[0] = point[0].coerceIn(0, width - 1)
    point[1] = point[1].coerceIn(0, height - 1)
    return point
}

private fun guideBounds(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): IntArray {
    if (viewWidth > 0 && viewHeight > 0) {
        val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val visibleWidth = viewWidth / scale
        val visibleHeight = viewHeight / scale
        val visibleLeft = (imageWidth - visibleWidth) / 2f
        val visibleTop = (imageHeight - visibleHeight) / 2f
        val cropWidth = visibleWidth * WIDTH_FRACTION
        val cropHeight = visibleHeight * HEIGHT_FRACTION
        return intArrayOf(
            (visibleLeft + (visibleWidth - cropWidth) / 2f).toInt().coerceAtLeast(0),
            (visibleTop + (visibleHeight - cropHeight) / 2f).toInt().coerceAtLeast(0),
            cropWidth.toInt().coerceAtLeast(1),
            cropHeight.toInt().coerceAtLeast(1)
        )
    }
    return intArrayOf(
        (imageWidth * (1f - WIDTH_FRACTION) / 2f).toInt(),
        (imageHeight * (1f - HEIGHT_FRACTION) / 2f).toInt(),
        (imageWidth * WIDTH_FRACTION).toInt().coerceAtLeast(1),
        (imageHeight * HEIGHT_FRACTION).toInt().coerceAtLeast(1)
    )
}
