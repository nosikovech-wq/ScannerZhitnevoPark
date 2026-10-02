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

private const val PLATE_LETTERS = "АВЕКМНРСТУХ"
private const val AMBIGUOUS = '#'

private data class PlateHit(val plate: String, val score: Int)

/** OCR text → plate, with О/0 chosen by the slot: letter slots become О, digit slots become 0. */
fun readPlate(raw: String): String? = fixPlate(preparePlateText(raw))

/** Same correction for a number the user confirms or that is already stored. */
fun correctPlate(raw: String): String = readPlate(raw) ?: normalizePlate(raw)

private fun preparePlateText(raw: String): String {
    return buildString {
        raw.uppercase().forEach { ch ->
            when {
                ch.isWhitespace() || ch == '-' || ch == '.' || ch == '_' -> Unit
                ch == 'O' || ch == 'О' || ch == '0' || ch == 'Ø' || ch == 'Θ' || ch == 'Ο' -> append(AMBIGUOUS)
                ch == 'I' || ch == 'L' || ch == '|' -> append('1')
                else -> append(LATIN_TO_CYR[ch] ?: ch)
            }
        }
    }
}

private fun isLetterish(ch: Char) = ch in PLATE_LETTERS || ch == AMBIGUOUS

private fun isDigitish(ch: Char) = ch.isDigit() || ch == AMBIGUOUS

private fun fixPlate(prepared: String): String? {
    var best: PlateHit? = null
    for (start in prepared.indices) {
        val rest = prepared.substring(start)
        best = better(best, take(rest, 8, 9, setOf(0, 4, 5), 100))
        best = better(best, take(rest, 8, 9, setOf(0, 1), 60))
        best = better(best, take(rest, 8, 9, setOf(4, 5), 20))
    }
    return best?.plate
}

/**
 * Standard letters are 0,4,5. Trailer letters are 0,1. Moto letters are 4,5.
 * A leading zero is scored as a car letter, not as the first digit of a moto plate.
 */
private fun take(text: String, short: Int, long: Int, letterAt: Set<Int>, base: Int): PlateHit? {
    var best: PlateHit? = null
    for (len in intArrayOf(long, short)) {
        if (text.length < len) continue
        val window = text.substring(0, len)
        if ((0 until 6).any { index ->
                val letter = index in letterAt
                if (letter) !isLetterish(window[index]) else !isDigitish(window[index])
            }
        ) continue
        if (!window.drop(6).all { isDigitish(it) }) continue
        val plate = rewrite(window, letterAt)
        if (!validRegion(plate.drop(6))) continue
        val realLetters = letterAt.count { window[it] in PLATE_LETTERS }
        var score = base + realLetters * 3
        if (4 in letterAt && 0 !in letterAt && window[0] == AMBIGUOUS) score -= 30
        best = better(best, PlateHit(plate, score))
    }
    return best
}

private fun rewrite(window: String, letterAt: Set<Int>): String = buildString {
    window.forEachIndexed { index, ch ->
        val letter = index in letterAt
        append(
            when (ch) {
                AMBIGUOUS, 'О', 'O', '0' -> if (letter) 'О' else '0'
                else -> ch
            }
        )
    }
}

private fun better(current: PlateHit?, next: PlateHit?): PlateHit? = when {
    current == null -> next
    next == null -> current
    next.score > current.score -> next
    else -> current
}

private fun validRegion(code: String): Boolean {
    val value = code.toIntOrNull() ?: return false
    if (value in 1..99) return true
    if (value !in 100..999) return false
    val base = value % 100
    return base in 1..99
}

/** Spaces for the journal: standard, trailer and motorcycle plates. */
fun formatPlateUi(number: String): String {
    val normalized = correctPlate(number)
    val letter = "[${PLATE_LETTERS}О]"
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
