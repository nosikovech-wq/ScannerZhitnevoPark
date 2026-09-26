package com.example.russianplatescanner.util

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

/** A plate cannot be stored again until this long after its last save. */
const val REPEAT_LOCK_MS = 24L * 60 * 60 * 1000

fun repeatWindowStart(now: Long = System.currentTimeMillis()): Long = now - REPEAT_LOCK_MS
