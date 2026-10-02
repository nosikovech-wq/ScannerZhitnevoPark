package com.example.russianplatescanner.util

import android.content.Context
import android.util.Base64
import com.example.russianplatescanner.data.PlateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object SheetSync {
    private const val PREFS = "sheet_sync"
    private const val KEY_URL = "url"
    const val DEFAULT_URL =
        "https://script.google.com/macros/s/AKfycbwJbIpqhI9lE25H9txc4j0umMeTrnuvez8HIiyx2cpdrFAPZ8bwH9kAEoi-ysAsykus2A/exec"

    data class Tick(
        val phase: String,
        val done: Int,
        val total: Int,
        val inserted: Int,
        val skipped: Int,
        val finished: Boolean = false,
        val error: String? = null
    )

    fun url(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "")
            .orEmpty()
        if (saved.isBlank() || saved.contains("AKfycbzN4wj3G-vXs_75xdFcNFcndEOR8nc")) {
            return DEFAULT_URL
        }
        return saved
    }

    fun saveUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, value.trim())
            .apply()
    }

    suspend fun upload(
        url: String,
        plates: List<PlateEntity>,
        isCancelled: () -> Boolean,
        onProgress: (Tick) -> Unit,
        onAccepted: suspend (List<String>) -> Unit = {}
    ): Tick = withContext(Dispatchers.IO) {
        suspend fun report(tick: Tick) {
            withContext(Dispatchers.Main) { onProgress(tick) }
        }
        try {
            report(Tick("Проверка номеров…", 0, 0, 0, 0))
            runCatching { syncNumbers(url, plates) }
            report(Tick("Проверка строк в таблице…", 0, 0, 0, 0))
            val remote = fetchStatus(url)
            onAccepted(remote.keys.toList())
            val pending = plates.filter { plate ->
                val known = remote[plate.recordKey()]
                known == null || !known
            }
            val already = plates.size - pending.size
            if (pending.isEmpty()) {
                val done = Tick(
                    phase = "Новых строк нет",
                    done = 0,
                    total = 0,
                    inserted = 0,
                    skipped = already,
                    finished = true
                )
                report(done)
                return@withContext done
            }
            var sent = 0
            var inserted = 0
            var skipped = already
            var sorted = false
            val total = pending.size
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            try {
                for (chunk in pending.chunked(6)) {
                    if (isCancelled()) {
                        if (sent > 0) {
                            report(Tick("Сортировка таблицы…", sent, total, inserted, skipped))
                            runCatching { finish(url) }
                            sorted = true
                        }
                        val cancelled = Tick("Остановлено", sent, total, inserted, skipped, finished = true)
                        report(cancelled)
                        return@withContext cancelled
                    }
                    val result = append(url, chunk, plates, fmt)
                    onAccepted(chunk.map { it.recordKey() })
                    sent += chunk.size
                    inserted += result.first
                    skipped += result.second
                    report(Tick("Отправка фото и строк…", sent, total, inserted, skipped))
                }
                if (sent > 0) {
                    report(Tick("Сортировка таблицы…", sent, total, inserted, skipped))
                    finish(url)
                    sorted = true
                }
                val done = Tick("Готово", total, total, inserted, skipped, finished = true)
                report(done)
                done
            } catch (e: Exception) {
                if (sent > 0 && !sorted) runCatching { finish(url) }
                throw e
            }
        } catch (e: Exception) {
            val failed = Tick(
                phase = "Не удалось выгрузить",
                done = 0,
                total = 0,
                inserted = 0,
                skipped = 0,
                finished = true,
                error = e.message ?: "Ошибка сети"
            )
            report(failed)
            failed
        }
    }

    data class RemoteRow(
        val id: String,
        val number: String,
        val timestamp: Long,
        val note: String?,
        val hasPhoto: Boolean
    )

    suspend fun knownIds(url: String): Set<String> = withContext(Dispatchers.IO) {
        fetchStatus(url).keys
    }

    suspend fun index(url: String): List<RemoteRow> = withContext(Dispatchers.IO) {
        fetchRows(url)
    }

    suspend fun clear(url: String) = withContext(Dispatchers.IO) {
        post(url, JSONObject().put("action", "clear").toString())
    }

    private fun fetchStatus(url: String): Map<String, Boolean> {
        return fetchRows(url).associate { it.id to it.hasPhoto }
    }

    private fun fetchRows(url: String): List<RemoteRow> {
        val response = post(url, JSONObject().put("action", "status").toString())
        val rows = response.optJSONArray("rows") ?: JSONArray()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                add(
                    RemoteRow(
                        id = id,
                        number = row.optString("number"),
                        timestamp = row.optLong("timestamp"),
                        note = row.optString("note").ifBlank { null },
                        hasPhoto = row.optBoolean("hasPhoto")
                    )
                )
            }
        }
    }

    private fun numberForSheet(plate: PlateEntity, plates: List<PlateEntity>): String {
        val resolved = FleetBook.resolve(plate.number)
        val stored = correctPlate(plate.number)
        val blocked = stored != resolved && plates.any { other ->
            other.id != plate.id &&
                correctPlate(other.number) == resolved &&
                abs(other.timestamp - plate.timestamp) < REPEAT_LOCK_MS
        }
        return if (blocked) FleetBook.label(stored, canonical = false) else FleetBook.label(plate.number)
    }

    private fun directoryOf(number: String): Pair<String, String> {
        val crew = FleetBook.match(number) ?: return "" to ""
        val trailer = if (crew.trailer.isBlank()) "" else FleetBook.label(crew.trailer, canonical = false)
        return trailer to crew.driver
    }

    private fun syncNumbers(url: String, plates: List<PlateEntity>) {
        if (plates.isEmpty()) return
        val rows = JSONArray()
        plates.forEach { plate ->
            val directory = directoryOf(plate.number)
            rows.put(
                JSONObject()
                    .put("id", plate.recordKey())
                    .put("number", numberForSheet(plate, plates))
                    .put("trailer", directory.first)
                    .put("driver", directory.second)
            )
        }
        post(url, JSONObject().put("action", "numbers").put("rows", rows).toString())
    }

    private fun append(
        url: String,
        chunk: List<PlateEntity>,
        plates: List<PlateEntity>,
        fmt: SimpleDateFormat
    ): Pair<Int, Int> {
        val rows = JSONArray()
        chunk.forEach { plate ->
            val photo = PhotoStorage.jpegBytesForUpload(plate.photoPath)
            val directory = directoryOf(plate.number)
            rows.put(
                JSONObject()
                    .put("id", plate.recordKey())
                    .put("date", fmt.format(Date(plate.timestamp)))
                    .put("number", numberForSheet(plate, plates))
                    .put("trailer", directory.first)
                    .put("driver", directory.second)
                    .put("note", plate.note ?: "")
                    .put("unauthorized", plate.unauthorizedExit)
                    .put("photoData", if (photo.isEmpty()) "" else Base64.encodeToString(photo, Base64.NO_WRAP))
            )
        }
        val response = post(url, JSONObject().put("action", "append").put("rows", rows).toString())
        return response.optInt("inserted") to response.optInt("skipped")
    }

    private fun finish(url: String) {
        post(url, JSONObject().put("action", "finish").toString())
    }

    suspend fun update(url: String, plate: PlateEntity, previousNumber: String): Boolean =
        withContext(Dispatchers.IO) {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            val directory = directoryOf(plate.number)
            val response = post(
                url,
                JSONObject()
                    .put("action", "update")
                    .put("id", plate.recordKey())
                    .put("number", FleetBook.label(plate.number))
                    .put("trailer", directory.first)
                    .put("driver", directory.second)
                    .put("oldNumber", previousNumber)
                    .put("date", fmt.format(Date(plate.timestamp)))
                    .put("note", plate.note ?: "")
                    .put("unauthorized", plate.unauthorizedExit)
                    .toString()
            )
            response.optBoolean("updated")
        }

    suspend fun remove(url: String, plate: PlateEntity): Boolean = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val response = post(
            url,
            JSONObject()
                .put("action", "remove")
                .put("id", plate.recordKey())
                .put("number", plate.number)
                .put("date", fmt.format(Date(plate.timestamp)))
                .toString()
        )
        response.optBoolean("removed")
    }

    private fun post(url: String, json: String): JSONObject {
        var current = url
        var sendBody = true
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = if (sendBody) "POST" else "GET"
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                if (sendBody) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }
            if (sendBody) {
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) error("Таблица не приняла запрос")
                current = if (next.startsWith("http")) next else URL(URL(current), next).toString()
                sendBody = false
                return@repeat
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299 || !text.trimStart().startsWith("{")) {
                error("Таблица не ответила. Проверьте адрес и доступ «Все».")
            }
            val body = JSONObject(text)
            if (!body.optBoolean("ok")) error(body.optString("error", "Ошибка таблицы"))
            return body
        }
        error("Не удалось открыть таблицу")
    }
}
