package ru.zhitnevo.park

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val UA = "ZhitnevoPark/1.0"
private const val PARTNER = "https://back.dornet.ru"
private val JSON = "application/json; charset=utf-8".toMediaType()

class CamFail(message: String) : Exception(message)
private class NeedAuth : Exception()

object Net {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Панель и камера часто с сертификатом на IP. Дорожная сеть идёт отдельным проверяемым клиентом. */
    val local: OkHttpClient = run {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    val camera: OkHttpClient = local.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    val partner: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
}

object Hik {
    fun streamId(s: AppSettings): Int {
        val channel = s.hikChannel.coerceIn(1, 32)
        return channel * 100 + if (s.hikSubstream) 2 else 1
    }

    fun hostOnly(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val host = runCatching { URI(withScheme).host }.getOrNull()?.trim().orEmpty()
        val value = host.ifEmpty { trimmed.substringBefore("/").substringBefore(":") }
        if (value.contains("@") || value.contains(" ")) return ""
        return value
    }

    fun rtspUrl(s: AppSettings): String {
        val host = hostOnly(s.hikHost)
        val user = Uri.encode(s.hikUser)
        val pass = Uri.encode(s.hikPassword)
        val auth = if (s.hikPassword.isEmpty()) user else "$user:$pass"
        return "rtsp://$auth@$host:${s.hikRtspPort}/Streaming/Channels/${streamId(s)}"
    }

    fun fetchPicture(s: AppSettings): ByteArray {
        val host = hostOnly(s.hikHost)
        if (host.isEmpty()) throw CamFail("камера не задана")
        val scheme = if (s.hikHttps) "https" else "http"
        val channel = s.hikChannel.coerceIn(1, 32)
        val paths = listOf(
            "/ISAPI/Streaming/channels/${streamId(s)}/picture",
            "/ISAPI/Streaming/channels/$channel/picture",
        )
        var sawAuth = false
        for (path in paths) {
            val url = "$scheme://$host:${s.hikHttpPort}$path"
            val bytes = try {
                getPicture(url, path, s.hikUser, s.hikPassword)
            } catch (e: CamFail) {
                if (e.message == "камера: неверный логин") sawAuth = true
                null
            }
            if (bytes != null) return bytes
        }
        throw CamFail(if (sawAuth) "камера: неверный логин" else "камера нет сигнала")
    }

    private fun getPicture(url: String, path: String, user: String, pass: String): ByteArray? {
        val first = Request.Builder().url(url).header("User-Agent", UA).get().build()
        Net.camera.newCall(first).execute().use { res ->
            if (res.code == 200) return jpeg(res.body?.bytes())
            if (res.code == 404) return null
            if (res.code != 401) return null
            val www = res.header("WWW-Authenticate").orEmpty()
            if (www.isBlank()) throw CamFail("камера: неверный логин")
            val header = authorization(www, "GET", path, user, pass)
            val second = first.newBuilder().header("Authorization", header).build()
            Net.camera.newCall(second).execute().use { again ->
                if (again.code == 200) return jpeg(again.body?.bytes())
                if (www.contains("Digest", ignoreCase = true)) {
                    val abs = authorization(www, "GET", url, user, pass)
                    val third = first.newBuilder().header("Authorization", abs).build()
                    Net.camera.newCall(third).execute().use { last ->
                        if (last.code == 200) return jpeg(last.body?.bytes())
                    }
                }
                if (again.code == 401 || again.code == 403) throw CamFail("камера: неверный логин")
                return null
            }
        }
    }

    private fun jpeg(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.size < 32 || bytes.size > 8_000_000) return null
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        return if (jpeg) bytes else null
    }

    private fun authorization(www: String, method: String, uri: String, user: String, pass: String): String {
        if (!www.contains("Digest", ignoreCase = true)) {
            val token = okhttp3.Credentials.basic(user, pass, Charsets.UTF_8)
            return token
        }
        val params = digestParams(www)
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val opaque = params["opaque"]
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val algorithm = params["algorithm"] ?: "MD5"
        val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val nc = "00000001"
        val ha1raw = "$user:$realm:$pass"
        val ha1 = if (algorithm.equals("MD5-sess", true)) {
            hash(algorithm, hash(algorithm, ha1raw) + ":$nonce:$cnonce")
        } else {
            hash(algorithm, ha1raw)
        }
        val ha2 = hash(algorithm, "$method:$uri")
        val response = if (qop != null) {
            hash(algorithm, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            hash(algorithm, "$ha1:$nonce:$ha2")
        }
        val parts = mutableListOf(
            "username=\"${user.replace("\"", "")}\"",
            "realm=\"$realm\"",
            "nonce=\"$nonce\"",
            "uri=\"$uri\"",
            "response=\"$response\"",
        )
        if (!algorithm.equals("MD5", true)) parts += "algorithm=$algorithm"
        if (qop != null) {
            parts += "qop=$qop"
            parts += "nc=$nc"
            parts += "cnonce=\"$cnonce\""
        }
        if (!opaque.isNullOrEmpty()) parts += "opaque=\"$opaque\""
        return "Digest " + parts.joinToString(", ")
    }

    private fun digestParams(header: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val re = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""")
        re.findAll(header).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return out
    }

    private fun hash(algorithm: String, data: String): String {
        val name = if (algorithm.contains("SHA-256", true)) "SHA-256" else "MD5"
        val digest = MessageDigest.getInstance(name).digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object Feeds {
    fun park(s: AppSettings): ParkRaw {
        if (s.username.isBlank() || s.password.isBlank()) {
            return ParkRaw(ok = false, error = "Нужны логин и пароль панели")
        }
        val origin = try {
            panelOrigin(s.server)
        } catch (e: IllegalArgumentException) {
            return ParkRaw(ok = false, error = e.message ?: "Некорректный адрес панели")
        }
        return try {
            val loginBody = JSONObject()
                .put("username", s.username)
                .put("password", s.password)
                .toString()
            val login = call(Net.local, "$origin/api/login", "POST", "", loginBody)
            val token = login.second?.optString("token").orEmpty()
            if (login.first >= 400 || token.isBlank() || token == "null") {
                return ParkRaw(ok = false, error = errorText(login.second, login.first, "Не удалось войти"))
            }
            val stats = call(Net.local, "$origin/api/stats", "GET", token, null)
            val body = stats.second
            if (stats.first >= 400 || body == null) {
                return ParkRaw(ok = false, error = errorText(body, stats.first, "Нет связи с сервером"))
            }
            val month = num(body, "monthNumber").takeIf { it in 1..12 } ?: moscowToday().monthValue
            ParkRaw(
                ok = true,
                day = num(body, "day"),
                month = num(body, "month"),
                monthNumber = month,
                date = body.optString("date"),
                last = firstTime(body),
                plate = firstPlate(body),
                dayMoney = moneyOrNull(body, "dayMoney"),
                monthMoney = moneyOrNull(body, "monthMoney"),
            )
        } catch (_: Exception) {
            ParkRaw(ok = false, error = "Нет связи с сервером")
        }
    }

    fun partner(s: AppSettings): PartnerRaw {
        if (s.partnerUser.isBlank() || s.partnerPassword.isBlank()) {
            return PartnerRaw(ok = false, error = "Нет входа Дорожной сети")
        }
        return try {
            val token = authPartner(s) ?: return PartnerRaw(ok = false, error = "Не удалось войти в Дорожную сеть")
            try {
                loadPartner(token)
            } catch (_: NeedAuth) {
                val again = authPartner(s) ?: return PartnerRaw(ok = false, error = "Нужно войти")
                loadPartner(again)
            }
        } catch (_: Exception) {
            PartnerRaw(ok = false, error = "Дорожная сеть недоступна")
        }
    }

    private fun authPartner(s: AppSettings): String? {
        val body = JSONObject().put("login", s.partnerUser).put("password", s.partnerPassword).toString()
        val login = call(Net.partner, "$PARTNER/api/account/auth", "POST", "", body)
        val token = login.second?.optString("token").orEmpty()
        if (login.first >= 400 || token.isBlank() || token == "null") return null
        return token
    }

    private fun loadPartner(token: String): PartnerRaw {
        val today = moscowToday()
        val tomorrow = today.plusDays(1)
        val monthStart = today.withDayOfMonth(1)
        val nextMonth = monthStart.plusMonths(1)
        val dayPark = partnerGet("/api/supplier/order/list?limit=1&start=0&dateFrom=$today&dateTo=$tomorrow&pointType=p", token)
        val monthPark = partnerGet("/api/supplier/order/list?limit=1&start=0&dateFrom=$monthStart&dateTo=$nextMonth&pointType=p", token)
        val daySvc = serviceCounts(today.toString(), tomorrow.toString(), token)
        val monthSvc = serviceCounts(monthStart.toString(), nextMonth.toString(), token)
        val reviews = partnerGet("/api/supplier/review/count", token)
        val count = num(reviews, "count")
        val latest = partnerGet("/api/supplier/review/list?limit=1&start=${(count - 1).coerceAtLeast(0)}", token)
        var reviewDate = "нет"
        var reviewStars = 0
        val latestItem = itemsOf(latest).firstOrNull()
        val created = latestItem?.optString("date_created").orEmpty()
        if (created.length >= 10) reviewDate = created.substring(0, 10).replace('-', '.')
        reviewStars = reviewStarsOf(latestItem)
        return PartnerRaw(
            ok = true,
            dayCount = num(dayPark, "total"),
            monthCount = num(monthPark, "total"),
            dayOfMonth = today.dayOfMonth,
            showerDay = daySvc.first,
            showerMonth = monthSvc.first,
            laundryDay = daySvc.second,
            laundryMonth = monthSvc.second,
            reviewDate = reviewDate,
            reviewStars = reviewStars,
        )
    }

    private fun serviceCounts(from: String, to: String, token: String): Pair<Int, Int> {
        var shower = 0
        var laundry = 0
        var start = 0
        var total = 1
        while (start < total && start < 800) {
            val page = partnerGet(
                "/api/supplier/order/list?limit=200&start=$start&dateFrom=$from&dateTo=$to&pointType=d",
                token,
            )
            total = num(page, "total")
            val items = itemsOf(page)
            if (items.isEmpty()) break
            for (item in items) {
                when (item.optString("orderType")) {
                    "Душ" -> shower += 1
                    "Прачечная" -> laundry += 1
                }
            }
            start += 200
        }
        return shower to laundry
    }

    private fun partnerGet(path: String, token: String): JSONObject {
        val res = call(Net.partner, "$PARTNER$path", "GET", token, null)
        if (res.first == 401) throw NeedAuth()
        if (res.first >= 400 || res.second == null) throw IllegalStateException("dornet")
        return res.second!!
    }

    private fun call(client: OkHttpClient, url: String, method: String, token: String, body: String?): Pair<Int, JSONObject?> {
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.method(method, body.toRequestBody(JSON)) else builder.method(method, null)
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (text.length > 2_000_000) return res.code to null
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (_: Exception) {
                null
            }
            return res.code to json
        }
    }

    private fun panelOrigin(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val with = when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            trimmed.isEmpty() -> throw IllegalArgumentException("Некорректный адрес панели")
            else -> "https://$trimmed"
        }
        val uri = URI(with)
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("Некорректный адрес панели")
        if (uri.userInfo != null) throw IllegalArgumentException("Логин и пароль укажите в полях ниже")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun errorText(json: JSONObject?, status: Int, fallback: String): String {
        val err = json?.optString("error")?.trim().orEmpty()
        if (err.isNotEmpty() && err != "null") return err
        if (status == 401) return "Нужно войти"
        if (status > 0) return "$status $fallback"
        return fallback
    }

    private fun num(json: JSONObject?, key: String): Int {
        if (json == null || !json.has(key) || json.isNull(key)) return 0
        return when (val v = json.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    private fun moneyOrNull(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val v = json.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun firstPlate(stats: JSONObject): String {
        val item = firstPlateItem(stats) ?: return ""
        for (key in listOf("tractor", "number", "plate", "gosNumber")) {
            val value = item.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun firstPlateItem(stats: JSONObject): JSONObject? {
        if (!stats.has("plates") || stats.isNull("plates")) return null
        return when (val plates = stats.get("plates")) {
            is JSONArray -> if (plates.length() == 0) null else plates.optJSONObject(0)
            is JSONObject -> plates
            else -> null
        }
    }

    private fun firstTime(stats: JSONObject): String {
        val item = firstPlateItem(stats) ?: return "нет"
        return item.optString("time").trim().ifEmpty { "нет" }
    }

    private fun reviewStarsOf(item: JSONObject?): Int {
        if (item == null) return 0
        for (key in listOf("rating", "stars", "star", "rate", "mark", "score", "grade", "ball")) {
            if (!item.has(key) || item.isNull(key)) continue
            val raw = when (val value = item.get(key)) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            } ?: continue
            val stars = when {
                raw in 0.0..5.0 -> raw
                raw in 5.0..10.0 -> raw / 2.0
                else -> continue
            }
            return kotlin.math.round(stars).toInt().coerceIn(0, 5)
        }
        return 0
    }

    private fun itemsOf(json: JSONObject?): List<JSONObject> {
        if (json == null || !json.has("items") || json.isNull("items")) return emptyList()
        return when (val items = json.get("items")) {
            is JSONArray -> (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            is JSONObject -> listOf(items)
            else -> emptyList()
        }
    }
}
