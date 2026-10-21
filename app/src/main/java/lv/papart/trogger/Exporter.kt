package lv.papart.trogger

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A cell is either text or a number; numbers stay numeric in Excel so totals can be summed. */
sealed interface Cell
data class T(val s: String, val bold: Boolean = false) : Cell
data class N(val v: Double, val decimals: Int = 2) : Cell

class Sheet(val name: String, val widths: List<Int>, val rows: List<List<Cell?>>)

object Exporter {

    private const val PURPOSE_HEADER = "Brauciena mērķis"

    fun tripHeader() = listOf(
        "Nr.", "Datums", "Sākums", "Beigas", "Maršruts", PURPOSE_HEADER, "Platforma",
        "Odometrs sākumā", "Odometrs beigās", "Nobraukts, km", "GPS, km", "Veids", "Piezīmes",
    )

    fun tripRow(n: Int, t: Trip): List<Cell?> = listOf(
        N(n.toDouble(), 0), T(fmtDate(t.startMs)), T(fmtTime(t.startMs)),
        T(t.endMs?.let { fmtTime(it) } ?: ""), T(t.route), T(t.purpose), T(t.platform),
        t.odoStart?.let { N(it, 0) }, t.odoEnd?.let { N(it, 0) },
        N(t.km, 1), N(t.gpsKm, 1), T(if (t.business) "Darba" else "Privāts"), T(t.note),
    )

    fun expenseHeader() = listOf(
        "Datums", "Kategorija", "Summa, EUR", "Litri", "Odometrs", "Pārdevējs",
        "Dokumenta nr.", "100% darbam", "Piezīmes",
    )

    fun expenseRow(e: Expense): List<Cell?> = listOf(
        T(fmtDate(e.dateMs)), T(e.category), N(e.amount), e.liters?.let { N(it) },
        e.odo?.let { N(it, 0) }, T(e.vendor), T(e.docNo), T(if (e.fullyBusiness) "Jā" else "Nē"), T(e.note),
    )

    fun buildWorkbook(
        year: Int,
        month: Int?,
        settings: Settings,
        trips: List<Trip>,
        expenses: List<Expense>,
        readings: List<OdoReading>,
    ): List<Sheet> {
        val period = Period.of(year, month)
        val pTrips = trips.filter { !it.active && it.startMs in period }.sortedBy { it.startMs }
        val pExp = expenses.filter { it.dateMs in period }.sortedBy { it.dateMs }
        val s = summarize(period, trips, expenses, readings)

        val header = listOf(
            listOf(T("Transportlīdzekļa ceļazīmes – ${period.label}", bold = true)),
            listOf(T("Vadītājs:"), T(settings.driver)),
            listOf(T("Transportlīdzeklis:"), T("${settings.carModel} ${settings.carReg}".trim())),
            listOf(T("Degvielas veids:"), T(settings.fuel)),
            emptyList(),
        )

        val tripRows = header + listOf(tripHeader().map { T(it, bold = true) }) +
            pTrips.mapIndexed { i, t -> tripRow(i + 1, t) } +
            listOf(
                emptyList(),
                listOf(T("Darba braucieni kopā, km", bold = true), null, null, null, null, null, null, null, null, N(s.businessKm, 1)),
                listOf(T("Privātie braucieni (reģistrētie), km", bold = true), null, null, null, null, null, null, null, null, N(s.privateLoggedKm, 1)),
            )

        val expRows = listOf(
            listOf(T("Transportlīdzekļa izdevumi – ${period.label}", bold = true)),
            emptyList(),
            expenseHeader().map { T(it, bold = true) },
        ) + pExp.map { expenseRow(it) } + listOf(
            emptyList(),
            listOf(T("Kopā", bold = true), null, N(s.expensesTotal)),
        )

        val sumRows = mutableListOf<List<Cell?>>(
            listOf(T("Kopsavilkums – ${period.label}", bold = true)),
            emptyList(),
            listOf(T("Kopējais nobraukums, km"), N(s.totalKm, 1)),
            listOf(T("  t.sk. pēc odometra, km"), s.odometerKm?.let { N(it, 1) } ?: T("nav pietiekamu rādījumu")),
            listOf(T("Darba braucieni, km"), N(s.businessKm, 1)),
            listOf(T("Privātie braucieni, km"), N(s.privateKm, 1)),
            listOf(T("Darba braucienu īpatsvars, %"), N(s.businessShare * 100, 1)),
            listOf(T("Maiņu skaits"), N(s.shifts.toDouble(), 0)),
            emptyList(),
            listOf(T("Izdevumi kopā, EUR"), N(s.expensesTotal)),
            listOf(T("  t.sk. 100% saimnieciskās darbības, EUR"), N(s.expensesFull)),
            listOf(T("  t.sk. dalāmie (× īpatsvars), EUR"), N(s.expensesShared)),
            listOf(T("Attiecināmie izdevumi, EUR", bold = true), N(s.deductible)),
            listOf(T("Degviela, litri"), N(s.fuelLiters)),
            emptyList(),
            listOf(T("Izdevumi pa kategorijām", bold = true)),
        )
        EXPENSE_CATEGORIES.forEach { c -> s.byCategory[c]?.let { sumRows += listOf(T(c), N(it)) } }
        if (month == null) {
            sumRows += emptyList<Cell?>()
            sumRows += listOf("Mēnesis", "Darba km", "Kopā km", "Īpatsvars, %", "Izdevumi, EUR").map { T(it, bold = true) }
            for (m in 0..11) {
                val ms = summarize(Period.of(year, m), trips, expenses, readings)
                sumRows += listOf(
                    T(MONTHS_LV[m]), N(ms.businessKm, 1), N(ms.totalKm, 1),
                    N(ms.businessShare * 100, 1), N(ms.expensesTotal),
                )
            }
        }
        sumRows += emptyList<Cell?>()
        sumRows += listOf(T("Īpatsvars = darba km / kopējais nobraukums. Attiecināmie = 100% izdevumi + dalāmie × īpatsvars."))

        return listOf(
            Sheet("Ceļazīmes", listOf(5, 11, 7, 7, 30, 30, 11, 10, 10, 10, 9, 8, 24), tripRows),
            Sheet("Izdevumi", listOf(11, 22, 11, 8, 10, 20, 14, 10, 24), expRows),
            Sheet("Kopsavilkums", listOf(42, 14, 12, 12, 14), sumRows),
        )
    }

    // ---- CSV: semicolon + decimal comma + BOM, which is what Latvian Excel opens cleanly ----

    fun writeCsv(out: OutputStream, header: List<String>, rows: List<List<Cell?>>) {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(";") { csv(it) }).append("\r\n")
        rows.forEach { r ->
            sb.append(r.joinToString(";") { c ->
                when (c) {
                    null -> ""
                    is T -> csv(c.s)
                    is N -> fmtNum(c.v, c.decimals)
                }
            }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun csv(s: String) =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---- minimal XLSX (Office Open XML) writer; no library needed for plain tables ----

    fun writeXlsx(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach {
                    append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${xml(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach {
                    append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            })
            // Styles: 0 default, 1 bold, 2 "0", 3 "0.0", 4 "0.00"
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="0.0"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5"><xf/><xf fontId="1" applyFont="1"/><xf numFmtId="1" applyNumberFormat="1"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="2" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun sheetXml(s: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>""")
        s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        s.rows.forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell ->
                val ref = colName(c) + (r + 1)
                when (cell) {
                    null -> Unit
                    is T -> if (cell.s.isNotEmpty()) append(
                        """<c r="$ref" t="inlineStr"${if (cell.bold) " s=\"1\"" else ""}><is><t xml:space="preserve">${xml(cell.s)}</t></is></c>"""
                    )
                    is N -> {
                        val style = when (cell.decimals) { 0 -> 2; 1 -> 3; else -> 4 }
                        append("""<c r="$ref" s="$style"><v>${java.math.BigDecimal(cell.v).setScale(cell.decimals, java.math.RoundingMode.HALF_UP).toPlainString()}</v></c>""")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun xml(s: String) = buildString {
        s.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }
}
