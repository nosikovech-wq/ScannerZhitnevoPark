package com.example.russianplatescanner.util

import android.content.Context
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

object SheetSync {
    private const val PREFS = "sheet_sync"
    private const val KEY_URL = "url"

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
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "")
            .orEmpty()
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
        onProgress: (Tick) -> Unit
    ): Tick = withContext(Dispatchers.IO) {
        suspend fun report(tick: Tick) {
            withContext(Dispatchers.Main) { onProgress(tick) }
        }
        try {
            report(Tick("Проверка строк в таблице…", 0, 0, 0, 0))
            val known = fetchIds(url)
            val pending = plates.filter { it.id.toString() !in known }
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
            val total = pending.size
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            for (chunk in pending.chunked(8)) {
                if (isCancelled()) {
                    val cancelled = Tick("Остановлено", sent, total, inserted, skipped, finished = true)
                    report(cancelled)
                    return@withContext cancelled
                }
                val result = append(url, chunk, fmt)
                sent += chunk.size
                inserted += result.first
                skipped += result.second
                report(Tick("Отправка строк…", sent, total, inserted, skipped))
            }
            val done = Tick("Готово", total, total, inserted, skipped, finished = true)
            report(done)
            done
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

    private fun fetchIds(url: String): Set<String> {
        val response = post(url, JSONObject().put("action", "ids").toString())
        val ids = response.optJSONArray("ids") ?: JSONArray()
        return buildSet {
            for (i in 0 until ids.length()) add(ids.optString(i))
        }
    }

    private fun append(
        url: String,
        chunk: List<PlateEntity>,
        fmt: SimpleDateFormat
    ): Pair<Int, Int> {
        val rows = JSONArray()
        chunk.forEach { plate ->
            rows.put(
                JSONObject()
                    .put("id", plate.id.toString())
                    .put("date", fmt.format(Date(plate.timestamp)))
                    .put("number", plate.number)
                    .put("note", plate.note ?: "")
                    .put("unauthorized", plate.unauthorizedExit)
                    .put("photo", plate.photoPath)
            )
        }
        val response = post(url, JSONObject().put("action", "append").put("rows", rows).toString())
        return response.optInt("inserted") to response.optInt("skipped")
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
