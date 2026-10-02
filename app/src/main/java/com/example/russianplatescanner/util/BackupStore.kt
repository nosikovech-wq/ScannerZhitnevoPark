package com.example.russianplatescanner.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreResult(val added: Int, val skipped: Int)

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

    suspend fun restore(context: Context, uri: Uri, dao: PlateDao): RestoreResult {
        val photos = HashMap<String, ByteArray>()
        var json = ""
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bytes = zip.readBytes()
                        if (entry.name.endsWith("plates.json")) {
                            json = bytes.toString(Charsets.UTF_8)
                        } else if (entry.name.contains("photos/")) {
                            photos[entry.name.substringAfterLast('/')] = bytes
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Не удалось открыть архив")
        if (json.isBlank()) error("В архиве нет базы")
        val known = dao.allUids().toMutableSet()
        val rows = JSONArray(json)
        val dir = File(context.filesDir, "plates").apply { mkdirs() }
        var added = 0
        var skipped = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val uid = row.optString("uid")
            if (uid.isBlank() || uid in known) {
                skipped++
                continue
            }
            val photoName = row.optString("photo")
            val bytes = if (photoName.isBlank()) null else photos[photoName]
            val photoPath = if (bytes == null) {
                ""
            } else {
                val dest = File(dir, photoName)
                val file = if (dest.exists()) File(dir, uid.take(8) + "_" + photoName) else dest
                file.writeBytes(bytes)
                file.absolutePath
            }
            dao.insert(
                PlateEntity(
                    number = row.optString("number"),
                    photoPath = photoPath,
                    timestamp = row.optLong("timestamp"),
                    note = row.optString("note").ifBlank { null },
                    unauthorizedExit = row.optBoolean("unauthorized"),
                    uploaded = row.optBoolean("uploaded"),
                    uid = uid
                )
            )
            known += uid
            added++
        }
        return RestoreResult(added, skipped)
    }
}
