package com.example.russianplatescanner.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object GuideCrop {
    private const val WIDTH_FRACTION = 0.82f
    private const val HEIGHT_FRACTION = 0.22f

    fun of(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth < 8 || imageHeight < 8) return bitmap
        val bounds = if (viewWidth > 0 && viewHeight > 0) {
            val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
            val visibleWidth = viewWidth / scale
            val visibleHeight = viewHeight / scale
            val visibleLeft = (imageWidth - visibleWidth) / 2f
            val visibleTop = (imageHeight - visibleHeight) / 2f
            val cropWidth = visibleWidth * WIDTH_FRACTION
            val cropHeight = visibleHeight * HEIGHT_FRACTION
            intArrayOf(
                (visibleLeft + (visibleWidth - cropWidth) / 2f).toInt(),
                (visibleTop + (visibleHeight - cropHeight) / 2f).toInt(),
                cropWidth.toInt(),
                cropHeight.toInt()
            )
        } else {
            intArrayOf(
                (imageWidth * (1f - WIDTH_FRACTION) / 2f).toInt(),
                (imageHeight * (1f - HEIGHT_FRACTION) / 2f).toInt(),
                (imageWidth * WIDTH_FRACTION).toInt(),
                (imageHeight * HEIGHT_FRACTION).toInt()
            )
        }
        val left = bounds[0].coerceIn(0, imageWidth - 1)
        val top = bounds[1].coerceIn(0, imageHeight - 1)
        val width = bounds[2].coerceIn(1, imageWidth - left)
        val height = bounds[3].coerceIn(1, imageHeight - top)
        if (width >= imageWidth - 2 && height >= imageHeight - 2) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}

internal fun ImageProxy.toUprightBitmap(): Bitmap {
    val nv21 = toNv21()
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val stream = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 85, stream)
    val bytes = stream.toByteArray()
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Пустой кадр")
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated != raw) raw.recycle()
    return rotated
}

private fun ImageProxy.toNv21(): ByteArray {
    val width = width
    val height = height
    val nv21 = ByteArray(width * height * 3 / 2)
    copyPlane(planes[0], nv21, 0, width, height)
    copyChroma(planes[2], planes[1], nv21, width * height, width / 2, height / 2)
    return nv21
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    out: ByteArray,
    offset: Int,
    width: Int,
    height: Int
) {
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(out, offset, width * height)
        return
    }
    var cursor = offset
    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        val length = min(rowStride, buffer.remaining())
        if (length <= 0) break
        buffer.get(row, 0, length)
        for (x in 0 until width) {
            val index = x * pixelStride
            if (index < length) out[cursor++] = row[index]
        }
    }
}

private fun copyChroma(
    vPlane: ImageProxy.PlaneProxy,
    uPlane: ImageProxy.PlaneProxy,
    out: ByteArray,
    offset: Int,
    width: Int,
    height: Int
) {
    val vBuffer = vPlane.buffer
    val uBuffer = uPlane.buffer
    vBuffer.rewind()
    uBuffer.rewind()
    val vRow = ByteArray(vPlane.rowStride)
    val uRow = ByteArray(uPlane.rowStride)
    var cursor = offset
    for (y in 0 until height) {
        val vLength = min(vPlane.rowStride, vBuffer.remaining())
        val uLength = min(uPlane.rowStride, uBuffer.remaining())
        if (vLength <= 0 || uLength <= 0) break
        vBuffer.get(vRow, 0, vLength)
        uBuffer.get(uRow, 0, uLength)
        for (x in 0 until width) {
            val vIndex = x * vPlane.pixelStride
            val uIndex = x * uPlane.pixelStride
            if (cursor + 1 < out.size && vIndex < vLength && uIndex < uLength) {
                out[cursor++] = vRow[vIndex]
                out[cursor++] = uRow[uIndex]
            }
        }
    }
}
