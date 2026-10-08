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

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    val settings = MutableStateFlow(Prefs.load(app))
    val park = MutableStateFlow(demoPark())
    val partner = MutableStateFlow(demoPartner())
    val parkState = MutableStateFlow("demo")
    val partnerState = MutableStateFlow("demo")
    val cameraBitmap = MutableStateFlow<Bitmap?>(null)
    val camStatus = MutableStateFlow("камера не задана")
    val sky = MutableStateFlow(Sky())
    private val generation = MutableStateFlow(0)

    init {
        viewModelScope.launch(Dispatchers.IO) { parkLoop() }
        viewModelScope.launch(Dispatchers.IO) { partnerLoop() }
        viewModelScope.launch(Dispatchers.IO) { cameraLoop() }
        viewModelScope.launch(Dispatchers.IO) { weatherLoop() }
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
        val url = "https://api.open-meteo.com/v1/forecast?latitude=55.34233&longitude=37.91464" +
            "&current=temperature_2m,weather_code&timezone=Europe%2FMoscow"
        while (true) {
            try {
                val body = Net.partner.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    res.body?.string().orEmpty()
                }
                val current = JSONObject(body).getJSONObject("current")
                val code = current.getInt("weather_code")
                val temp = current.getDouble("temperature_2m").roundToInt()
                sky.value = Sky(
                    temp = "${if (temp > 0) "+" else ""}$temp°",
                    code = code,
                    label = weatherLabel(code),
                )
            } catch (_: Exception) {
                if (sky.value.code < 0) sky.value = Sky(label = "нет данных")
            }
            delay(15 * 60_000)
        }
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
