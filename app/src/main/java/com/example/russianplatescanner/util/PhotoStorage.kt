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

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return file.absolutePath
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
