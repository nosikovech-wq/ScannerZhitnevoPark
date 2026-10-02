package com.example.russianplatescanner.util

import android.content.Context

data class Crew(
    val tractor: String,
    val trailer: String,
    val driver: String
)

object FleetBook {
    fun load(context: Context): Map<String, Crew> {
        val text = runCatching {
            context.assets.open("fleet.csv").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        val map = LinkedHashMap<String, Crew>()
        parse(text).forEach { crew ->
            val tractor = correctPlate(crew.tractor)
            val trailer = correctPlate(crew.trailer)
            if (tractor.isNotBlank()) map.putIfAbsent(tractor, crew)
            if (trailer.isNotBlank()) map.putIfAbsent(trailer, crew)
        }
        return map
    }

    private fun parse(text: String): List<Crew> {
        val lines = text
            .split('\n')
            .map { it.trim('\r', ' ', '\uFEFF') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyList()
        val delimiter = if (lines.first().count { it == ';' } >= lines.first().count { it == ',' }) ';' else ','
        val rows = lines.map { line -> line.split(delimiter).map { it.trim().trim('"') } }
        val header = rows.first().map { it.lowercase() }
        val hasHeader = header.any { it.contains("тягач") || it.contains("фио") || it.contains("вод") || it.contains("прицеп") }
        val body = if (hasHeader) rows.drop(1) else rows
        val tractorCol = column(header, hasHeader, "тягач", 0)
        val trailerCol = column(header, hasHeader, "прицеп", 1)
        val driverCol = column(header, hasHeader, "фио", 2).let { found ->
            if (hasHeader && found < 0) column(header, true, "вод", 2) else found
        }
        return body.mapNotNull { cells ->
            fun at(index: Int) = cells.getOrNull(index).orEmpty()
            val tractor = at(tractorCol)
            val trailer = at(trailerCol)
            val driver = at(driverCol)
            if (tractor.isBlank() && trailer.isBlank()) null else Crew(tractor, trailer, driver)
        }
    }

    private fun column(header: List<String>, hasHeader: Boolean, name: String, fallback: Int): Int {
        if (!hasHeader) return fallback
        val index = header.indexOfFirst { it.contains(name) }
        return if (index >= 0) index else fallback
    }
}
