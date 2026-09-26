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

private const val PLATE_LETTERS = "АВЕКМНОРСТУХ"

/** Spaces for the journal: standard, trailer and motorcycle plates. */
fun formatPlateUi(number: String): String {
    val normalized = normalizePlate(number)
    val letter = "[$PLATE_LETTERS]"
    Regex("^($letter)(\\d{3})($letter{2})(\\d{2,3})$").find(normalized)?.let { match ->
        return "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]} ${match.groupValues[4]}"
    }
    Regex("^($letter{2})(\\d{4})(\\d{2,3})$").find(normalized)?.let { match ->
        return "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]}"
    }
    Regex("^(\\d{4})($letter{2})(\\d{2,3})$").find(normalized)?.let { match ->
        return "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]}"
    }
    return number
}

/** Local midnight. Used for the daily counter, not for the repeat lock. */
fun startOfLocalDay(now: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** First moment of the current local month. */
fun startOfLocalMonth(now: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = startOfLocalDay(now)
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
    return cal.timeInMillis
}
