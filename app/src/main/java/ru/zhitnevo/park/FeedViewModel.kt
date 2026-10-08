package ru.zhitnevo.park

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt

data class AirUi(val known: Boolean = false, val danger: Boolean = false, val mentionedAt: Long = 0)

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    val settings = MutableStateFlow(Prefs.load(app))
    val park = MutableStateFlow(demoPark())
    val partner = MutableStateFlow(demoPartner())
    val parkState = MutableStateFlow("demo")
    val partnerState = MutableStateFlow("demo")
    val cameraBitmap = MutableStateFlow<Bitmap?>(null)
    val camStatus = MutableStateFlow("камера не задана")
    val sky = MutableStateFlow(Sky())
    val bpla = MutableStateFlow(AirUi())
    private val generation = MutableStateFlow(0)

    init {
        viewModelScope.launch(Dispatchers.IO) { parkLoop() }
        viewModelScope.launch(Dispatchers.IO) { partnerLoop() }
        viewModelScope.launch(Dispatchers.IO) { cameraLoop() }
        viewModelScope.launch(Dispatchers.IO) { weatherLoop() }
        viewModelScope.launch(Dispatchers.IO) { airLoop() }
    }

    fun save(next: AppSettings) {
        val host = Hik.hostOnly(next.hikHost)
        val clean = next.copy(
            server = next.server.trim().ifBlank { AppSettings().server },
            username = next.username.trim(),
            partnerUser = next.partnerUser.trim(),
            hikHost = host,
            hikUser = next.hikUser.trim().ifBlank { "admin" },
            hikChannel = next.hikChannel.coerceIn(1, 32),
            hikHttpPort = next.hikHttpPort.coerceIn(1, 65535),
            hikRtspPort = next.hikRtspPort.coerceIn(1, 65535),
            dim = next.dim.coerceIn(0.12f, 0.72f),
        )
        Prefs.save(getApplication(), clean)
        settings.value = clean
        generation.value = generation.value + 1
    }

    fun reportCamera(text: String) {
        camStatus.value = text
    }

    private suspend fun parkLoop() {
        while (true) {
            val stamp = generation.value
            val s = settings.value
            if (s.username.isBlank() || s.password.isBlank()) {
                park.value = demoPark()
                parkState.value = "demo"
            } else {
                if (parkState.value != "live") parkState.value = "loading"
                val raw = withContext(Dispatchers.IO) { Feeds.park(s) }
                park.value = parkFrom(raw)
                parkState.value = if (raw.ok) "live" else "error"
            }
            waitUntil(180_000, stamp)
        }
    }

    private suspend fun partnerLoop() {
        while (true) {
            val stamp = generation.value
            val s = settings.value
            if (s.partnerUser.isBlank() || s.partnerPassword.isBlank()) {
                partner.value = demoPartner()
                partnerState.value = "demo"
            } else {
                if (partnerState.value != "live") partnerState.value = "loading"
                val raw = withContext(Dispatchers.IO) { Feeds.partner(s) }
                partner.value = partnerFrom(raw)
                partnerState.value = if (raw.ok) "live" else "error"
            }
            waitUntil(600_000, stamp)
        }
    }

    private suspend fun cameraLoop() {
        while (true) {
            val s = settings.value
            if (s.hikHost.isBlank()) {
                cameraBitmap.value = null
                camStatus.value = "камера не задана"
                delay(1000)
                continue
            }
            if (s.hikLive) {
                delay(1000)
                continue
            }
            try {
                val bytes = withContext(Dispatchers.IO) { Hik.fetchPicture(s) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    cameraBitmap.value = bmp
                    camStatus.value = "камера снимок"
                } else {
                    camStatus.value = "камера нет сигнала"
                }
            } catch (e: Exception) {
                camStatus.value = e.message?.takeIf { it.startsWith("камера") } ?: "камера нет сигнала"
            }
            delay(4000)
        }
    }

    private suspend fun weatherLoop() {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=55.34233&lon=37.91464"
        while (true) {
            val ok = try {
                val body = Net.partner.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "ZhitnevoPark/1.0 github.com/nosikovech-wq")
                        .build(),
                ).execute().use { res ->
                    if (!res.isSuccessful) error("погода ${res.code}")
                    res.body?.string().orEmpty()
                }
                val now = JSONObject(body)
                    .getJSONObject("properties")
                    .getJSONArray("timeseries")
                    .getJSONObject(0)
                    .getJSONObject("data")
                val temp = now.getJSONObject("instant")
                    .getJSONObject("details")
                    .getDouble("air_temperature")
                    .roundToInt()
                val symbol = now.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
                    ?: now.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
                    ?: "cloudy"
                val code = symbolToCode(symbol)
                sky.value = Sky(
                    temp = "${if (temp > 0) "+" else ""}$temp°",
                    code = code,
                    label = weatherLabel(code),
                )
                true
            } catch (_: Exception) {
                if (sky.value.code < 0) sky.value = Sky(label = "Житнево")
                false
            }
            delay(if (ok) 20 * 60_000L else 60_000L)
        }
    }

    private suspend fun airLoop() {
        val url = "https://radar-map.ru/api/state"
        while (true) {
            try {
                val body = Net.partner.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "ZhitnevoPark/1.0")
                        .header("Accept", "application/json")
                        .build(),
                ).execute().use { res ->
                    if (!res.isSuccessful) error("карта ${res.code}")
                    res.body?.string().orEmpty()
                }
                bpla.value = domodedovoAir(JSONObject(body))
            } catch (_: Exception) {
                // оставляем последний известный статус
            }
            delay(60_000)
        }
    }

    private fun domodedovoAir(root: JSONObject): AirUi {
        val from = System.currentTimeMillis() / 1000 - 3600
        var latest = 0L
        val cities = root.optJSONArray("cities")
        if (cities != null) {
            for (i in 0 until cities.length()) {
                val city = cities.optJSONObject(i) ?: continue
                val blob = city.optString("name") + " " + city.optString("key") + " " + city.optString("region")
                if (!blob.contains("домодедово", ignoreCase = true)) continue
                if (!blob.contains("москов", ignoreCase = true)) continue
                val ts = epochSec(city.optLong("last_event_ts"))
                if (ts > latest) latest = ts
            }
        }
        val messages = root.optJSONArray("recent_messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                if (!msg.optString("text").contains("домодедово", ignoreCase = true)) continue
                val ts = epochSec(msg.optLong("ts"))
                if (ts > latest) latest = ts
            }
        }
        return AirUi(known = true, danger = latest >= from && latest > 0, mentionedAt = latest)
    }

    private fun epochSec(ts: Long): Long {
        if (ts <= 0) return 0
        return if (ts > 10_000_000_000L) ts / 1000 else ts
    }

    private fun symbolToCode(symbol: String): Int = when (symbol.substringBefore("_")) {
        "clearsky" -> 0
        "fair" -> 1
        "partlycloudy" -> 2
        "cloudy" -> 3
        "fog" -> 45
        "lightrain", "rain", "lightrainshowers", "rainshowers" -> 61
        "heavyrain", "heavyrainshowers" -> 65
        "lightsleet", "sleet", "heavysleet", "lightsleetshowers", "sleetshowers" -> 67
        "lightsnow", "snow", "lightsnowshowers", "snowshowers" -> 71
        "heavysnow", "heavysnowshowers" -> 75
        "rainandthunder", "heavyrainandthunder", "rainshowersandthunder", "heavyrainshowersandthunder",
        "sleetandthunder", "snowandthunder",
        -> 95
        else -> 3
    }

    private fun weatherLabel(code: Int) = when (code) {
        0 -> "ясно"
        1, 2 -> "мало облаков"
        3 -> "облачно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "дождь"
        71, 73, 75, 77, 85, 86 -> "снег"
        95, 96, 99 -> "гроза"
        else -> "Житнево"
    }

    private suspend fun waitUntil(ms: Long, stamp: Int) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && generation.value == stamp) {
            delay(400)
        }
    }
}
