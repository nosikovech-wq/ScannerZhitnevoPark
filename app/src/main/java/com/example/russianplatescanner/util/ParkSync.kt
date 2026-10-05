package com.example.russianplatescanner.util

import android.content.Context
import android.util.Base64
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ParkUser(val id: Int, val username: String, val role: String)

object ParkSync {
    private const val PREFS = "park_sync"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"
    private const val KEY_ROLE = "role"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_FLEET = "fleet_rev"
    private const val KEY_DELETES = "deletes"

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty()

    fun saveUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, value.trim().trimEnd('/'))
            .apply()
    }

    fun username(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, "").orEmpty()

    fun role(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROLE, "").orEmpty()

    fun isAdmin(context: Context): Boolean = role(context) == "admin"

    fun loggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty().isNotBlank()

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .remove(KEY_ROLE)
            .apply()
    }

    fun start(app: PlateApp, scope: CoroutineScope) {
        scope.launch {
            while (true) {
                if (loggedIn(app)) {
                    runCatching { cycle(app) }
                }
                delay(3000)
            }
        }
    }

    fun login(context: Context, username: String, password: String): String {
        val response = post(context, "/api/login", JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .toString(), auth = false)
        val token = response.getString("token")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, response.getString("username"))
            .putString(KEY_ROLE, response.getString("role"))
            .apply()
        return response.getString("role")
    }

    fun users(context: Context): List<ParkUser> {
        val response = get(context, "/api/users")
        val array = response.getJSONArray("users")
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                add(ParkUser(row.getInt("id"), row.getString("username"), row.getString("role")))
            }
        }
    }

    fun createUser(context: Context, username: String, password: String, admin: Boolean) {
        post(context, "/api/users", JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("role", if (admin) "admin" else "operator")
            .toString())
    }

    fun deleteUser(context: Context, id: Int) {
        request(context, "DELETE", "/api/users/$id", null)
    }

    suspend fun pushOne(context: Context, dao: PlateDao, plate: PlateEntity) {
        if (!loggedIn(context)) return
        val body = JSONObject()
            .put("uid", plate.recordKey())
            .put("number", plate.number)
            .put("timestamp", plate.timestamp)
            .put("note", plate.note ?: "")
            .put("unauthorized", plate.unauthorizedExit)
        val photo = PhotoStorage.jpegBytesForUpload(plate.photoPath)
        if (photo.isNotEmpty()) {
            body.put("photo", Base64.encodeToString(photo, Base64.NO_WRAP))
        }
        post(context, "/api/plates", body.toString())
        dao.markSynced(plate.recordKey())
    }

    fun rememberDelete(context: Context, uid: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_DELETES, emptySet()).orEmpty().toMutableSet()
        next.add(uid)
        prefs.edit().putStringSet(KEY_DELETES, next).apply()
    }

    fun pushFleet(context: Context) {
        if (!isAdmin(context)) return
        val rows = JSONArray()
        FleetBook.crews().forEach { crew ->
            rows.put(JSONObject()
                .put("tractor", crew.tractor)
                .put("trailer", crew.trailer)
                .put("driver", crew.driver))
        }
        val response = request(context, "PUT", "/api/fleet", JSONObject().put("rows", rows).toString())
        val revision = response.optLong("revision")
        if (revision > 0) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_FLEET, revision).apply()
        }
    }

    fun importBackup(context: Context, zip: ByteArray): String {
        val response = request(context, "POST", "/api/import", null, raw = zip, contentType = "application/zip", readMs = 180000)
        return "На сервер добавлено: ${response.optInt("inserted")}. Уже были: ${response.optInt("skipped")}."
    }

    private suspend fun cycle(app: PlateApp) {
        val dao = app.plateDao
        flushDeletes(app)
        dao.pending().forEach { plate ->
            runCatching { pushOne(app, dao, plate) }
        }
        pull(app, dao)
    }

    private fun flushDeletes(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = prefs.getStringSet(KEY_DELETES, emptySet()).orEmpty().toMutableSet()
        val done = mutableListOf<String>()
        left.forEach { uid ->
            val ok = runCatching { request(context, "DELETE", "/api/plates/$uid", null) }.isSuccess
            if (ok) done.add(uid)
        }
        if (done.isNotEmpty()) {
            left.removeAll(done.toSet())
            prefs.edit().putStringSet(KEY_DELETES, left).apply()
        }
    }

    private suspend fun pull(context: Context, dao: PlateDao) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_CURSOR, 0L)
        val response = get(context, "/api/changes?since=$since")
        val plates = response.getJSONArray("plates")
        for (index in 0 until plates.length()) {
            applyPlate(context, dao, plates.getJSONObject(index))
        }
        prefs.edit().putLong(KEY_CURSOR, response.optLong("serverTime", since)).apply()
        val revision = response.optLong("fleetRevision")
        if (revision > 0 && revision != prefs.getLong(KEY_FLEET, 0L)) {
            applyFleet(context, revision)
        }
    }

    private fun applyFleet(context: Context, revision: Long) {
        val response = get(context, "/api/fleet")
        val rows = response.getJSONArray("rows")
        val crews = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                add(Crew(row.optString("tractor"), row.optString("trailer"), row.optString("driver")))
            }
        }
        if (crews.isNotEmpty()) {
            FleetBook.saveLocal(context, crews)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_FLEET, revision).apply()
    }

    private suspend fun applyPlate(context: Context, dao: PlateDao, row: JSONObject) {
        val uid = row.getString("uid")
        val local = dao.findByUid(uid)
        if (row.optBoolean("deleted")) {
            if (local != null && !local.pendingSync) {
                PhotoStorage.deletePhoto(local.photoPath)
                dao.delete(local)
            }
            return
        }
        if (local != null && local.pendingSync) return
        val photoPath = when {
            local != null && File(local.photoPath).isFile -> local.photoPath
            row.optBoolean("hasPhoto") -> downloadPhoto(context, uid)
            else -> local?.photoPath.orEmpty()
        }
        val plate = PlateEntity(
            id = local?.id ?: 0,
            number = row.optString("number"),
            photoPath = photoPath,
            timestamp = row.optLong("timestamp"),
            note = row.optString("note").ifBlank { null },
            unauthorizedExit = row.optBoolean("unauthorized"),
            uploaded = local?.uploaded ?: false,
            uid = uid,
            pendingSync = false
        )
        if (local == null) dao.insert(plate) else dao.update(plate)
    }

    private fun downloadPhoto(context: Context, uid: String): String {
        val bytes = requestBytes(context, "/api/photos/$uid")
        if (bytes.isEmpty()) return ""
        val dir = File(context.filesDir, "plates").apply { mkdirs() }
        val file = File(dir, "$uid.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun get(context: Context, path: String): JSONObject = request(context, "GET", path, null)

    private fun post(context: Context, path: String, json: String, auth: Boolean = true): JSONObject =
        request(context, "POST", path, json, auth = auth)

    private fun request(
        context: Context,
        method: String,
        path: String,
        json: String?,
        auth: Boolean = true,
        raw: ByteArray? = null,
        contentType: String = "application/json; charset=UTF-8",
        readMs: Int = 30000
    ): JSONObject {
        val base = url(context)
        if (!base.startsWith("http://") && !base.startsWith("https://")) error("Не указан адрес сервера")
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = readMs
            doInput = true
            if (auth) {
                val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (json != null || raw != null) {
                doOutput = true
                setRequestProperty("Content-Type", if (raw != null) contentType else "application/json; charset=UTF-8")
            }
        }
        if (json != null) conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        if (raw != null) conn.outputStream.use { it.write(raw) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) logout(context)
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error(message.ifBlank { "Сервер ответил $code" })
        }
        if (text.isBlank()) return JSONObject()
        return JSONObject(text)
    }

    private fun requestBytes(context: Context, path: String): ByteArray {
        val base = url(context)
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 30000
            val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()
            setRequestProperty("Authorization", "Bearer $token")
        }
        if (conn.responseCode !in 200..299) return ByteArray(0)
        return conn.inputStream.use { it.readBytes() }
    }
}
