package com.example.russianplatescanner.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.russianplatescanner.data.PlateEntity
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object CsvExporter {
    @Suppress("SpellCheckingInspection")
    private const val FONT = "Calibri"
    private val months = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    )

    fun share(context: Context, plates: List<PlateEntity>) {
        val file = File(context.cacheDir, "TransportnyyeTekhnologii.xls")
        file.writeText(toExcelXml(plates, FleetBook.load(context)), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Экспорт таблицы"))
    }

    private fun toExcelXml(plates: List<PlateEntity>, fleet: Map<String, Crew>): String {
        val calendar = Calendar.getInstance()
        val grouped = plates.groupBy { plate ->
            calendar.timeInMillis = plate.timestamp
            calendar.get(Calendar.YEAR) to calendar.get(Calendar.MONTH)
        }.toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val monthsToWrite = grouped.ifEmpty {
            val now = Calendar.getInstance()
            mapOf((now.get(Calendar.YEAR) to now.get(Calendar.MONTH)) to emptyList())
        }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<?mso-application progid="Excel.Sheet"?>""")
            append("""<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">""")
            append("""<Styles>""")
            append("""<Style ss:ID="Title"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="$FONT" ss:Size="14" ss:Bold="1"/></Style>""")
            append("""<Style ss:ID="Date"><Alignment ss:Horizontal="Center" ss:Vertical="Center"/><Font ss:FontName="$FONT" ss:Size="11" ss:Bold="1"/><NumberFormat ss:Format="dd.mmm"/></Style>""")
            append("""<Style ss:ID="Plate"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="$FONT" ss:Size="14" ss:Bold="1"/></Style>""")
            append("""<Style ss:ID="Time"><Alignment ss:Horizontal="Center" ss:Vertical="Center"/><Font ss:FontName="$FONT" ss:Size="14"/><NumberFormat ss:Format="hh:mm:ss"/></Style>""")
            append("""<Style ss:ID="Info"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="$FONT" ss:Size="12"/></Style>""")
            append("""</Styles>""")
            monthsToWrite.forEach { (key, monthPlates) ->
                append(monthSheet(key.first, key.second, monthPlates, fleet))
            }
            append("</Workbook>")
        }
    }

    private fun monthSheet(
        year: Int,
        month: Int,
        plates: List<PlateEntity>,
        fleet: Map<String, Crew>
    ): String {
        val days = daysInMonth(year, month)
        val byTractor = plates.groupBy { plate ->
            val scanned = correctPlate(plate.number)
            val crew = fleet[scanned]
            val tractor = crew?.tractor?.let { correctPlate(it) }.orEmpty()
            if (tractor.isNotBlank()) tractor else scanned
        }
        val order = byTractor.keys.sortedBy { formatTractor(it) }
        return buildString {
            append("""<Worksheet ss:Name="${xml(months[month] + " " + year)}"><Table>""")
            append("""<Column ss:AutoFitWidth="0" ss:Width="150"/>""")
            append("""<Column ss:AutoFitWidth="0" ss:Width="140"/>""")
            append("""<Column ss:AutoFitWidth="0" ss:Width="220"/>""")
            repeat(days) {
                append("""<Column ss:AutoFitWidth="0" ss:Width="62"/>""")
            }
            append("<Row>")
            append("""<Cell ss:StyleID="Title"><Data ss:Type="String">Номер тягача</Data></Cell>""")
            append("""<Cell ss:StyleID="Title"><Data ss:Type="String">Номер прицепа</Data></Cell>""")
            append("""<Cell ss:StyleID="Title"><Data ss:Type="String">ФИО</Data></Cell>""")
            for (day in 1..days) {
                val serial = excelSerial(year, month, day).toInt()
                append("""<Cell ss:StyleID="Date"><Data ss:Type="Number">$serial</Data></Cell>""")
            }
            append("</Row>")
            order.forEach { tractorKey ->
                val visits = byTractor.getValue(tractorKey)
                val crew = visits.firstNotNullOfOrNull { fleet[correctPlate(it.number)] }
                val byDay = visits.groupBy { dayOf(it.timestamp) }
                append("<Row>")
                append("""<Cell ss:StyleID="Plate"><Data ss:Type="String">${xml(formatTractor(crew?.tractor?.ifBlank { null } ?: tractorKey))}</Data></Cell>""")
                append(textCell(crew?.trailer?.let { formatTractor(it) }.orEmpty()))
                append(textCell(crew?.driver.orEmpty()))
                for (day in 1..days) {
                    val first = byDay[day]?.minByOrNull { it.timestamp }
                    if (first == null) {
                        append("<Cell/>")
                    } else {
                        val fraction = timeFraction(first.timestamp)
                        append(
                            """<Cell ss:StyleID="Time"><Data ss:Type="Number">${"%.8f".format(Locale.US, fraction)}</Data></Cell>"""
                        )
                    }
                }
                append("</Row>")
            }
            append("</Table></Worksheet>")
        }
    }

    private fun textCell(value: String): String {
        if (value.isBlank()) return "<Cell/>"
        return """<Cell ss:StyleID="Info"><Data ss:Type="String">${xml(value)}</Data></Cell>"""
    }

    private fun formatTractor(number: String): String {
        val normalized = correctPlate(number)
        Regex("^([АВЕКМНОРСТУХ]\\d{3}[АВЕКМНОРСТУХ]{2})(\\d{2,3})$").find(normalized)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        Regex("^([АВЕКМНОРСТУХ]{2}\\d{4})(\\d{2,3})$").find(normalized)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        Regex("^(\\d{4}[АВЕКМНОРСТУХ]{2})(\\d{2,3})$").find(normalized)?.let {
            return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        return normalized
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, 1)
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun dayOf(timestamp: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun timeFraction(timestamp: Long): Double {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val seconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 +
            calendar.get(Calendar.SECOND)
        return seconds / 86400.0
    }

    private fun excelSerial(year: Int, month: Int, day: Int): Double {
        val date = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        date.set(year, month, day, 0, 0, 0)
        date.set(Calendar.MILLISECOND, 0)
        val epoch = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        epoch.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        epoch.set(Calendar.MILLISECOND, 0)
        return (date.timeInMillis - epoch.timeInMillis) / 86400000.0
    }

    private fun xml(value: String): String {
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '&' -> append("&" + "amp;")
                    '<' -> append("&" + "lt;")
                    '>' -> append("&" + "gt;")
                    '"' -> append("&" + "quot;")
                    else -> append(ch)
                }
            }
        }
    }
}
