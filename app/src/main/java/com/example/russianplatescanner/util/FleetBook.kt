package com.example.russianplatescanner.util

import android.content.Context
import java.io.File

data class Crew(
    val tractor: String,
    val trailer: String,
    val driver: String,
    val phone: String = ""
)

object FleetBook {
    private val gate = Any()
    private var byPlate: Map<String, Crew> = emptyMap()
    private var tractors: List<String> = emptyList()

    fun load(): Map<String, Crew> = synchronized(gate) { byPlate }

    fun crews(): List<Crew> = synchronized(gate) {
        byPlate.values.distinctBy { correctPlate(it.tractor) }
    }

    fun match(raw: String): Crew? {
        val normalized = correctPlate(raw)
        val plates = load()
        plates[normalized]?.let { return it }
        val resolved = resolve(raw)
        return if (resolved == normalized) null else plates[resolved]
    }

    fun install(crews: List<Crew>) {
        val map = LinkedHashMap<String, Crew>()
        crews.forEach { crew ->
            val tractor = correctPlate(crew.tractor)
            val trailer = correctPlate(crew.trailer)
            if (tractor.isNotBlank()) map.putIfAbsent(tractor, crew)
            if (trailer.isNotBlank()) map.putIfAbsent(trailer, crew)
        }
        synchronized(gate) {
            byPlate = map
            tractors = map.values.map { correctPlate(it.tractor) }.filter { it.length >= 8 }.distinct()
        }
    }

    fun loadLocal(context: Context) {
        val file = File(context.filesDir, "fleet.csv")
        if (!file.isFile || file.length() == 0L) return
        val crews = parse(file.readText(Charsets.UTF_8))
        if (crews.isNotEmpty()) install(crews)
    }

    fun saveLocal(context: Context, crews: List<Crew>) {
        val file = File(context.filesDir, "fleet.csv")
        file.writeText(
            buildString {
                append("Номер тягача;Номер прицепа;ФИО;Телефон\n")
                crews.forEach { crew ->
                    append(crew.tractor.trim())
                    append(';')
                    append(crew.trailer.trim())
                    append(';')
                    append(crew.driver.trim())
                    append(';')
                    append(crew.phone.trim())
                    append('\n')
                }
            },
            Charsets.UTF_8
        )
        install(crews)
    }

    /** Exact fleet plate, or the same plate body with a region that only missed the first digit. */
    fun resolve(raw: String): String {
        val normalized = correctPlate(raw)
        if (normalized.isBlank()) return normalized
        val plates: Map<String, Crew>
        val known: List<String>
        synchronized(gate) {
            plates = byPlate
            known = tractors
        }
        plates[normalized]?.let { crew ->
            return correctPlate(crew.tractor).ifBlank { normalized }
        }
        val body = if (normalized.length >= 8) normalized.take(6) else return normalized
        val region = normalized.drop(6)
        val matches = known.filter { tractor ->
            val fleetRegion = tractor.drop(6)
            tractor.startsWith(body) &&
                fleetRegion.length == region.length + 1 &&
                fleetRegion.endsWith(region)
        }
        return if (matches.size == 1) matches.first() else normalized
    }

    fun label(raw: String, canonical: Boolean = true): String {
        val number = if (canonical) resolve(raw) else correctPlate(raw)
        Regex("^([АВЕКМНОРСТУХ]\\d{3}[АВЕКМНОРСТУХ]{2})(\\d{2,3})$").find(number)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        Regex("^([АВЕКМНОРСТУХ]{2}\\d{4})(\\d{2,3})$").find(number)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        Regex("^(\\d{4}[АВЕКМНОРСТУХ]{2})(\\d{2,3})$").find(number)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        return number
    }

    private fun parse(text: String): List<Crew> {
        val lines = text
            .split('\n')
            .map { it.trim('\r', ' ', '\uFEFF') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyList()
        val delimiter = if (lines.first().count { it == ';' } >= lines.first().count { it == ',' }) ';' else ','
        val parsed = lines.map { line -> line.split(delimiter).map { it.trim().trim('"') } }
        val header = parsed.first().map { it.lowercase() }
        val hasHeader = header.any { it.contains("тягач") || it.contains("фио") || it.contains("вод") || it.contains("прицеп") }
        val data = if (hasHeader) parsed.drop(1) else parsed
        val tractorCol = column(header, hasHeader, "тягач", 0)
        val trailerCol = column(header, hasHeader, "прицеп", 1)
        val driverCol = column(header, hasHeader, "фио", 2).let { found ->
            if (hasHeader && found < 0) column(header, true, "вод", 2) else found
        }
        val phoneCol = column(header, hasHeader, "тел", 3)
        return data.mapNotNull { cells ->
            fun at(index: Int) = cells.getOrNull(index).orEmpty()
            val tractor = at(tractorCol)
            val trailer = at(trailerCol)
            val driver = at(driverCol)
            val phone = at(phoneCol)
            if (tractor.isBlank() && trailer.isBlank()) null else Crew(tractor, trailer, driver, phone)
        }
    }

    private fun column(header: List<String>, hasHeader: Boolean, name: String, fallback: Int): Int {
        if (!hasHeader) return fallback
        val index = header.indexOfFirst { it.contains(name) }
        return if (index >= 0) index else fallback
    }
}
