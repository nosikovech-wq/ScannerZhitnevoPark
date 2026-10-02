package com.example.russianplatescanner.util

import android.content.Context
import java.io.File

data class Crew(
    val tractor: String,
    val trailer: String,
    val driver: String
)

object FleetBook {
    private val table = """
Номер тягача;Номер прицепа;ФИО
А099КК 761;СО3268 61;Горщар Сергей Антонович
А122ЕВ 761;СЕ6125 61;Шамшурин Юрий Николаевич
А673ВВ 761;СН0247 61;Беланов Яков Анатольевич
А716ВВ 761;СМ7952 61;Шуленин Валентин Сергеевич
А738ВВ 761;СН6889 61;Сегадетдинов Олег Зекиевич
В089ЕВ 716;СЕ4150 61;Гребчуков Валерий Леонидович
В131ЕВ 716;СК0932 61;Сурков Николай Александрович
В191ЕВ 716;СЕ7806 61;Гущин Андрей Юрьевич
В211ЕВ 716;СК2950 61;Васянович Денис Анатольевич
В320ВУ 716;СЕ8724 61;Руденко Алексей Александрович
В411АХ 761;СК5456 61;Бабинец Александр Георгиевич
В454АХ 761;СЕ2721 61;Кравцов Павел Анатольевич
В491АХ 761;СЕ7530 61;Царев Михаил Васильевич
В493АХ 761;СК4977 61;Куковский Вячеслав Валентинович
Е236КВ 761;СН7341 61;Ковтышний Иван Викторович
Е271КВ 761;СН7291 61;Школяр Валерий Алексеевич
Е281КВ 761;СН7870 61;Агеев Михаил Иванович
Е423НС 761;СЕ9303 61;Леонов Эдуард Николаевич
Е590НС 761;СН6324 61;Юсупов Аслан Шамсудинович
Е606КВ 761;СК1859 61;Уксукпаев Мурат Сундеткалиевич
Е647КК 761;СН6875 61;Вяткин Андрей Николаевич
Е678КВ 761;СН7866 61;Донченко Василий Алексеевич
Е683КВ 761;СЕ4122 61;Бахтин Александр Николаевич
Е703КВ 761;СН7862 61;Горгодзе Омари Омариевич
К288ВА 761;СЕ7471 61;Лаврентьев Герман Николаевич
К711ВТ 761;СК5473 61;Аджиев Арсен Алимсолтанович
К736ВТ 761;СК4955 61;Батыров Карамутдин Хакимович
К838ВТ 761;СЕ5041 61;Ермак Виталий Викторович
К840ВТ 761;СМ0867 61;Полтавченко Денис Викторович
М138НХ 761;СР3223 61;Кропивка Максим Сергеевич
М418ВТ 761;СК4976 61;Резниченко Роман Валериевич
М679АК 761;СН6879 61;Швейков Николай Николаевич
Н797СВ 761;СК1861 61;Архипов Юрий Виталиевич
Н982ХХ 161;СР3106 61;Косоножкин Александр Федорович
О026ТУ 161;СЕ9309 61;Исмаилов Асламбек Алаудинович
О081ТУ 161;СН6868 61;Ямпольский Алексей Иванович
О557КК 761;СЕ2722 61;Стребков Вячеслав Николаевич
О633ХТ 161;СЕ6510 61;Магомедсаидов Сулеймангаджи И.
О637ХТ 161;СЕ7814 61;Дагенов Александр Дмитриевич
О685ТУ 161;АТ0713 16;Авдусенко Михаил Валериевич
О756ТУ 161;СЕ4355 61;Айдамиров Руслан Даккаевич
О791ВТ 761;СО3271 61;Елисеев Игорь Анатольевич
О797ТУ 161;СК4950 61;Зайтуев Магомедсалам Омарович
О817ВТ 761;СО3257 61;Петросов Александр Арсеньевич
Р345ТТ 161;АУ0510 16;Горшенев Владимир Михайлович
Р631МО 761;АТ9049 16;Бричевский Павел Анатольевич
Р732НХ 761;СК4963 61;Оржановский Сергей Николаевич
С030МК 761;СК1853 61;Беликов Вадим Иванович
С057МК 761;СК5468 61;Мирзоев Расим Агаметович
С063МК 761;АТ6691 16;Ардашаев Руслан Хасаншевич
С078МК 761;СН0676 61;Исаков Асланбек Хасанович
Т148АЕ 761;АС6394 16;Боровиков Эдуард Николаевич
Т250АЕ 761;АТ9035 16;Жигрин Сергей Валерьевич
Т871МК 761;СК4953 61;Гаспаров Иван Игоревич
У228ЕТ 761;СМ9093 61;Куликов Денис Андреевич
У271ЕТ 761;СМ9092 61;Васильченко Евгений Александрович
У333ЕТ 761;СН0663 61;Першин Михаил Владимирович
У382ЕТ 761;СН0658 61;Прудников Роман Сергеевич
У739АТ 761;СК5470 61;Метансин Виктор Яковлевич
У780НХ 761;СЕ6503 61;Козырев Сергей Александрович
Х049АТ 761;СЕ7812 61;Лукьянов Евгений Николаевич
Х161АТ 761;СК4957 61;Фильчуков Владимир Александрович
Х175АТ 761;АУ1689 16;Середа Дмитрий Николаевич
Х192АТ 761;СК4974 61;Самарский Роман Викторович
Х331АТ 761;СЕ9319 61;Хитров Виктор Сергеевич
Х353НХ 761;СЕ9275 61;Жаботинский Николай Петрович
Х663ХО 161;СН6881 61;Щербина Виталий Николаевич
Х666ЕТ 761;СН0206 61;Евсеев Петр Климович
Х671АХ 761;СН6876 61;Шучков Владислав Александрович
Х694АХ 761;СЕ6142 61;Воробьев Алексей Александрович
Х714ХО 161;СН6348 61;Хренов Юрий Алексеевич
Х771АХ 761;СЕ5057 61;Гнездилов Василий Васильевич
"""

    private val gate = Any()
    private var byPlate: Map<String, Crew> = emptyMap()
    private var tractors: List<String> = emptyList()

    init {
        install(parse(table))
    }

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
        if (!file.isFile || file.length() == 0L) {
            file.writeText(table.trim() + "\n", Charsets.UTF_8)
        }
        val crews = parse(file.readText(Charsets.UTF_8))
        if (crews.isNotEmpty()) install(crews)
    }

    fun saveLocal(context: Context, crews: List<Crew>) {
        val file = File(context.filesDir, "fleet.csv")
        file.writeText(
            buildString {
                append("Номер тягача;Номер прицепа;ФИО\n")
                crews.forEach { crew ->
                    append(crew.tractor.trim())
                    append(';')
                    append(crew.trailer.trim())
                    append(';')
                    append(crew.driver.trim())
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
        return data.mapNotNull { cells ->
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
