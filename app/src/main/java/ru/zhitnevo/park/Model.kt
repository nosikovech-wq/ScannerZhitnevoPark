package ru.zhitnevo.park

import android.content.Context
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

data class AppSettings(
    val server: String = "https://168.113.210.66",
    val username: String = "",
    val password: String = "",
    val partnerUser: String = "",
    val partnerPassword: String = "",
    val hikHost: String = "",
    val hikHttpPort: Int = 80,
    val hikRtspPort: Int = 554,
    val hikUser: String = "admin",
    val hikPassword: String = "",
    val hikChannel: Int = 1,
    val hikHttps: Boolean = false,
    val hikLive: Boolean = false,
    val hikSubstream: Boolean = true,
    val dim: Float = 0.28f,
)

data class ParkRaw(
    val ok: Boolean,
    val error: String = "",
    val day: Int = 0,
    val month: Int = 0,
    val monthNumber: Int = 1,
    val date: String = "",
    val last: String = "нет",
    val dayMoney: Int? = null,
    val monthMoney: Int? = null,
)

data class PartnerRaw(
    val ok: Boolean,
    val error: String = "",
    val dayCount: Int = 0,
    val monthCount: Int = 0,
    val dayOfMonth: Int = 1,
    val showerDay: Int = 0,
    val showerMonth: Int = 0,
    val laundryDay: Int = 0,
    val laundryMonth: Int = 0,
    val reviewDate: String = "нет",
)

data class ParkUi(
    val ready: Boolean,
    val offline: Boolean,
    val status: String,
    val day: String,
    val dayMoney: String,
    val monthLabel: String,
    val monthCount: String,
    val monthMoney: String,
    val avg: String,
    val last: String,
)

data class PartnerUi(
    val ready: Boolean,
    val status: String,
    val today: String,
    val month: String,
    val avg: String,
    val shower: String,
    val laundry: String,
    val reviewDate: String,
    val parkDayMoney: String,
    val parkMonthMoney: String,
    val showerMoney: String,
    val laundryMoney: String,
    val totalDay: String,
    val totalMonth: String,
)

data class Metric(val label: String, val value: String, val detail: String? = null)

object ParkColors {
    const val INK = 0xFF0B0C0EL
    const val FG = 0xFFF1F2F4L
    const val MUTED = 0xFFA7ADB6L
    const val MONEY = 0xFF7DBA8AL
    const val PARTNER = 0xFF7EBAE0L
    const val LINE = 0x33FFFFFFL
}

private val RU = Locale("ru", "RU")
private val MONTHS = arrayOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)
val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
const val PARK_RATE = 350
const val SERVICE_RATE = 180

fun moscowToday(): LocalDate = LocalDate.now(MOSCOW)

fun clockText(now: ZonedDateTime = ZonedDateTime.now(MOSCOW)): Pair<String, String> {
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm", RU))
    val date = now.format(DateTimeFormatter.ofPattern("d MMMM", RU))
    return time to date
}

private fun noun(n: Int, one: String, few: String, many: String): String {
    val a = abs(n) % 100
    val b = a % 10
    return when {
        a in 11..14 -> many
        b == 1 -> one
        b in 2..4 -> few
        else -> many
    }
}

fun machines(n: Int) = "$n ${noun(n, "машина", "машины", "машин")}"

fun money(n: Int): String = NumberFormat.getIntegerInstance(RU).format(n) + " ₽"

fun moneyPair(a: Int, b: Int) = "${money(a)} · ${money(b)}"

fun formatAverage(count: Int, day: Int): String {
    val d = if (day < 1) 1 else day
    val value = round(count.toDouble() / d * 10.0) / 10.0
    val fmt = NumberFormat.getNumberInstance(RU).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }
    return "${fmt.format(value)} в день"
}

fun monthName(monthNumber: Int): String {
    val i = (monthNumber - 1).coerceIn(0, 11)
    return MONTHS[i]
}

fun demoPark(): ParkUi {
    val day = moscowToday().dayOfMonth
    val dayCount = 42
    val monthCount = 248
    return ParkUi(
        ready = true,
        offline = false,
        status = "",
        day = machines(dayCount),
        dayMoney = money(14_700),
        monthLabel = monthName(moscowToday().monthValue),
        monthCount = machines(monthCount),
        monthMoney = money(86_800),
        avg = formatAverage(monthCount, day),
        last = "17:42",
    )
}

fun demoPartner(): PartnerUi {
    val day = moscowToday().dayOfMonth
    val dayCount = 18
    val monthCount = 410
    val showerDay = 4
    val showerMonth = 96
    val laundryDay = 2
    val laundryMonth = 41
    val parkDayMoney = dayCount * PARK_RATE
    val parkMonthMoney = monthCount * PARK_RATE
    val showerDayMoney = showerDay * SERVICE_RATE
    val showerMonthMoney = showerMonth * SERVICE_RATE
    val laundryDayMoney = laundryDay * SERVICE_RATE
    val laundryMonthMoney = laundryMonth * SERVICE_RATE
    return PartnerUi(
        ready = true,
        status = "",
        today = machines(dayCount),
        month = machines(monthCount),
        avg = formatAverage(monthCount, day),
        shower = "$showerDay · $showerMonth",
        laundry = "$laundryDay · $laundryMonth",
        reviewDate = "2026.10.07",
        parkDayMoney = money(parkDayMoney),
        parkMonthMoney = money(parkMonthMoney),
        showerMoney = moneyPair(showerDayMoney, showerMonthMoney),
        laundryMoney = moneyPair(laundryDayMoney, laundryMonthMoney),
        totalDay = money(parkDayMoney + showerDayMoney + laundryDayMoney),
        totalMonth = money(parkMonthMoney + showerMonthMoney + laundryMonthMoney),
    )
}

private val emptyPark = ParkUi(false, false, "Подключение…", "—", "—", "За месяц", "—", "—", "—", "—")
private val emptyPartner = PartnerUi(false, "Подключение…", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—")

fun parkFrom(raw: ParkRaw?): ParkUi {
    if (raw == null) return emptyPark
    if (!raw.ok) return emptyPark.copy(offline = true, status = raw.error.ifBlank { "Нет связи с сервером" })
    val dayNum = raw.date.takeIf { it.length >= 10 }?.substring(8, 10)?.toIntOrNull() ?: moscowToday().dayOfMonth
    return ParkUi(
        ready = true,
        offline = false,
        status = "",
        day = machines(raw.day),
        dayMoney = raw.dayMoney?.let { money(it) } ?: "—",
        monthLabel = monthName(raw.monthNumber),
        monthCount = machines(raw.month),
        monthMoney = raw.monthMoney?.let { money(it) } ?: "—",
        avg = formatAverage(raw.month, dayNum),
        last = raw.last.ifBlank { "нет" },
    )
}

fun partnerFrom(raw: PartnerRaw?): PartnerUi {
    if (raw == null) return emptyPartner
    if (!raw.ok) return emptyPartner.copy(status = raw.error.ifBlank { "Дорожная сеть недоступна" })
    val parkDayMoney = raw.dayCount * PARK_RATE
    val parkMonthMoney = raw.monthCount * PARK_RATE
    val showerDayMoney = raw.showerDay * SERVICE_RATE
    val showerMonthMoney = raw.showerMonth * SERVICE_RATE
    val laundryDayMoney = raw.laundryDay * SERVICE_RATE
    val laundryMonthMoney = raw.laundryMonth * SERVICE_RATE
    return PartnerUi(
        ready = true,
        status = "",
        today = machines(raw.dayCount),
        month = machines(raw.monthCount),
        avg = formatAverage(raw.monthCount, raw.dayOfMonth),
        shower = "${raw.showerDay} · ${raw.showerMonth}",
        laundry = "${raw.laundryDay} · ${raw.laundryMonth}",
        reviewDate = raw.reviewDate,
        parkDayMoney = money(parkDayMoney),
        parkMonthMoney = money(parkMonthMoney),
        showerMoney = moneyPair(showerDayMoney, showerMonthMoney),
        laundryMoney = moneyPair(laundryDayMoney, laundryMonthMoney),
        totalDay = money(parkDayMoney + showerDayMoney + laundryDayMoney),
        totalMonth = money(parkMonthMoney + showerMonthMoney + laundryMonthMoney),
    )
}

fun parkMetrics(park: ParkUi) = listOf(
    Metric(if (park.offline) "Сегодня, нет связи" else "Сегодня", park.day, park.dayMoney),
    Metric(park.monthLabel, park.monthCount, park.monthMoney),
    Metric("Среднее за день", park.avg),
    Metric("Последняя машина", park.last),
)

fun partnerMetrics(partner: PartnerUi) = listOf(
    Metric("Стоянка сегодня", partner.today, partner.parkDayMoney),
    Metric("Стоянка за месяц", partner.month, partner.parkMonthMoney),
    Metric("Среднее за день", partner.avg),
    Metric("Душ, сегодня/мес.", partner.shower, partner.showerMoney),
    Metric("Прачечная, сегодня/мес.", partner.laundry, partner.laundryMoney),
    Metric("Последний отзыв", partner.reviewDate),
    Metric("Всего за день", partner.totalDay),
    Metric("Всего за месяц", partner.totalMonth),
)

object Prefs {
    private const val FILE = "zhitnevo"

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AppSettings(
            server = p.getString("server", null) ?: AppSettings().server,
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: "",
            partnerUser = p.getString("partnerUser", "") ?: "",
            partnerPassword = p.getString("partnerPassword", "") ?: "",
            hikHost = p.getString("hikHost", "") ?: "",
            hikHttpPort = p.getInt("hikHttpPort", 80),
            hikRtspPort = p.getInt("hikRtspPort", 554),
            hikUser = p.getString("hikUser", "admin") ?: "admin",
            hikPassword = p.getString("hikPassword", "") ?: "",
            hikChannel = p.getInt("hikChannel", 1),
            hikHttps = p.getBoolean("hikHttps", false),
            hikLive = p.getBoolean("hikLive", false),
            hikSubstream = p.getBoolean("hikSubstream", true),
            dim = p.getFloat("dim", 0.28f),
        )
    }

    fun save(context: Context, s: AppSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("server", s.server)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("partnerUser", s.partnerUser)
            .putString("partnerPassword", s.partnerPassword)
            .putString("hikHost", s.hikHost)
            .putInt("hikHttpPort", s.hikHttpPort)
            .putInt("hikRtspPort", s.hikRtspPort)
            .putString("hikUser", s.hikUser)
            .putString("hikPassword", s.hikPassword)
            .putInt("hikChannel", s.hikChannel)
            .putBoolean("hikHttps", s.hikHttps)
            .putBoolean("hikLive", s.hikLive)
            .putBoolean("hikSubstream", s.hikSubstream)
            .putFloat("dim", s.dim)
            .apply()
    }
}
