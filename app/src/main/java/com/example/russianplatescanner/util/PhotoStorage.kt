package com.example.russianplatescanner.util

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object PhotoStorage {

    fun savePhoto(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "plates")
        if (!dir.exists()) dir.mkdirs()

        val filename = "plate_${System.currentTimeMillis()}.jpg"
        val file = File(dir, filename)
        val scaled = scaleDown(bitmap, 1280)

        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return file.absolutePath
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun deletePhoto(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    fun deleteAll(context: Context, paths: List<String>) {
        paths.forEach { deletePhoto(it) }
        val dir = File(context.filesDir, "plates")
        dir.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
