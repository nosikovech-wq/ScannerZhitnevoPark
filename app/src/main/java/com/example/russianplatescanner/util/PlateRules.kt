package com.example.russianplatescanner.util

import java.util.Calendar

private val LATIN_TO_CYR = mapOf(
    'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М',
    'H' to 'Н', 'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т',
    'Y' to 'У', 'X' to 'Х'
)

/** Same shape as the web scanner: upper case, no separators, Latin lookalikes → Cyrillic. */
fun normalizePlate(raw: String): String {
    return buildString {
        raw.uppercase().forEach { ch ->
            if (ch.isWhitespace() || ch == '-' || ch == '.' || ch == '_') return@forEach
            append(LATIN_TO_CYR[ch] ?: ch)
        }
    }
}

/** Local midnight — one plate may be stored once per calendar day. */
fun startOfLocalDay(now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
