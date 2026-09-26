package com.example.russianplatescanner.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.russianplatescanner.data.PlateEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {
    private val headers = listOf(
        "ДАТА",
        "НОМЕР",
        "ЗАМЕТКА",
        "НЕСОГЛАСОВАННЫЙ ВЫЕЗД",
        "ПУТЬ К ФОТО"
    )

    fun share(context: Context, plates: List<PlateEntity>) {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val rows = plates.map { plate ->
            listOf(
                fmt.format(Date(plate.timestamp)),
                plate.number,
                plate.note ?: "",
                if (plate.unauthorizedExit) "ДА" else "",
                plate.photoPath
            )
        }
        val file = File(context.cacheDir, "nomera.xls")
        file.writeText(toExcelXml(rows), Charsets.UTF_8)
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

    private fun toExcelXml(rows: List<List<String>>): String {
        val widths = headers.indices.map { col ->
            val longest = (sequenceOf(headers[col]) + rows.asSequence().map { it[col] })
                .maxOf { it.length }
            (longest.coerceAtLeast(8) * 7.5 + 18.0).coerceAtMost(720.0)
        }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<?mso-application progid="Excel.Sheet"?>""")
            append("""<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">""")
            append("""<Styles><Style ss:ID="Header"><Font ss:FontName="Calibri" ss:Size="11" ss:Bold="1"/></Style></Styles>""")
            append("""<Worksheet ss:Name="Номера"><Table>""")
            widths.forEach { width ->
                append("""<Column ss:AutoFitWidth="0" ss:Width="${"%.1f".format(Locale.US, width)}"/>""")
            }
            append("<Row>")
            headers.forEach { header ->
                append("""<Cell ss:StyleID="Header"><Data ss:Type="String">${xml(header)}</Data></Cell>""")
            }
            append("</Row>")
            rows.forEach { row ->
                append("<Row>")
                row.forEach { cell ->
                    append("""<Cell><Data ss:Type="String">${xml(cell)}</Data></Cell>""")
                }
                append("</Row>")
            }
            append("</Table></Worksheet></Workbook>")
        }
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
