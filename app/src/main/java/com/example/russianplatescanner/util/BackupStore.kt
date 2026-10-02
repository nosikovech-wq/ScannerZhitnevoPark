package com.example.russianplatescanner.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.russianplatescanner.data.PlateEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupStore {
    fun share(context: Context, plates: List<PlateEntity>) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "ZhitnevoPark-$stamp.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val rows = JSONArray()
            plates.sortedBy { it.timestamp }.forEach { plate ->
                val photo = File(plate.photoPath)
                val photoName = if (photo.isFile) photo.name else ""
                if (photo.isFile) {
                    zip.putNextEntry(ZipEntry("photos/$photoName"))
                    photo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                rows.put(
                    JSONObject()
                        .put("uid", plate.recordKey())
                        .put("number", plate.number)
                        .put("timestamp", plate.timestamp)
                        .put("note", plate.note ?: "")
                        .put("unauthorized", plate.unauthorizedExit)
                        .put("uploaded", plate.uploaded)
                        .put("photo", photoName)
                )
            }
            zip.putNextEntry(ZipEntry("plates.json"))
            zip.write(rows.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Бэкап базы"))
    }
}
