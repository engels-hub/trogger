package lv.papart.trogger.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lv.papart.trogger.EXPENSE_CATEGORIES
import lv.papart.trogger.Exporter
import lv.papart.trogger.MONTHS_LV
import lv.papart.trogger.OdoReading
import lv.papart.trogger.Period
import lv.papart.trogger.Repo
import lv.papart.trogger.fmtDate
import lv.papart.trogger.fmtEur
import lv.papart.trogger.fmtKm
import lv.papart.trogger.fmtNum
import lv.papart.trogger.parseNum
import lv.papart.trogger.summarize
import java.util.Calendar

@Composable
fun ReportsScreen() {
    val ctx = LocalContext.current
    val trips by Repo.trips.collectAsState()
    val expenses by Repo.expenses.collectAsState()
    val readings by Repo.readings.collectAsState()
    val settings by Repo.settings.collectAsState()

    val now = Calendar.getInstance()
    var year by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf<Int?>(null) }
    var editingReading by remember { mutableStateOf<OdoReading?>(null) }

    val period = Period.of(year, month)
    val s = summarize(period, trips, expenses, readings)
    val fileBase = "trogger_${year}" + (month?.let { "_%02d".format(it + 1) } ?: "")

    fun done(ok: Boolean) =
        Toast.makeText(ctx, if (ok) "Fails saglabāts" else "Neizdevās saglabāt failu", Toast.LENGTH_SHORT).show()

    val xlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) done(runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use {
                Exporter.writeXlsx(it, Exporter.buildWorkbook(year, month, settings, trips, expenses, readings))
            }
        }.isSuccess)
    }
    val tripsCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) done(runCatching {
            val list = trips.filter { !it.active && it.startMs in period }.sortedBy { it.startMs }
            ctx.contentResolver.openOutputStream(uri)!!.use {
                Exporter.writeCsv(it, Exporter.tripHeader(), list.mapIndexed { i, t -> Exporter.tripRow(i + 1, t) })
            }
        }.isSuccess)
    }
    val expCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) done(runCatching {
            val list = expenses.filter { it.dateMs in period }.sortedBy { it.dateMs }
            ctx.contentResolver.openOutputStream(uri)!!.use {
                Exporter.writeCsv(it, Exporter.expenseHeader(), list.map { e -> Exporter.expenseRow(e) })
            }
        }.isSuccess)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Iepriekšējais gads") }
            Text("$year", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nākamais gads") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = month == null, onClick = { month = null }, label = { Text("Viss gads") })
            MONTHS_LV.forEachIndexed { i, m ->
                FilterChip(selected = month == i, onClick = { month = i }, label = { Text(m.take(3)) })
            }
        }

        SectionCard {
            Text(period.label, style = MaterialTheme.typography.titleMedium)
            StatRow("Kopējais nobraukums", "${fmtKm(s.totalKm)} km")
            StatRow("Darba braucieni (${s.shifts})", "${fmtKm(s.businessKm)} km")
            StatRow("Privātie braucieni", "${fmtKm(s.privateKm)} km")
            StatRow("Darba īpatsvars", "${fmtKm(s.businessShare * 100)} %", strong = true)
            if (s.odometerKm == null) {
                Text(
                    "Nav odometra rādījumu pirms un perioda beigās, tāpēc privātie km ir tikai reģistrētie braucieni. " +
                        "Pievienojiet rādījumu perioda sākumā (piem. 1. janvārī), lai īpatsvars būtu precīzs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard {
            Text("Izdevumi", style = MaterialTheme.typography.titleMedium)
            EXPENSE_CATEGORIES.forEach { c -> s.byCategory[c]?.let { StatRow(c, fmtEur(it)) } }
            if (s.fuelLiters > 0) StatRow("Degviela", "${fmtNum(s.fuelLiters, 2)} l")
            StatRow("Kopā", fmtEur(s.expensesTotal))
            StatRow("Attiecināmie (${fmtKm(s.businessShare * 100)} % + 100% izdevumi)", fmtEur(s.deductible), strong = true)
        }

        SectionCard {
            Text("Eksports – ${period.label}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { xlsx.launch("$fileBase.xlsx") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, null); Text("  Excel (ceļazīmes, izdevumi, kopsavilkums)")
            }
            OutlinedButton(onClick = { tripsCsv.launch("${fileBase}_braucieni.csv") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, null); Text("  CSV – braucieni")
            }
            OutlinedButton(onClick = { expCsv.launch("${fileBase}_izdevumi.csv") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, null); Text("  CSV – izdevumi")
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Odometra rādījumi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val start = Calendar.getInstance().apply { clear(); set(year, 0, 1) }.timeInMillis
                    editingReading = OdoReading(
                        dateMs = if (readings.none { it.dateMs in Period.of(year, null) }) start else System.currentTimeMillis(),
                        value = 0.0,
                    )
                }) { Icon(Icons.Default.Add, "Pievienot rādījumu") }
            }
            Text(
                "Rādījumi gada sākumā un beigās nosaka kopējo nobraukumu, tātad arī privāto km daļu. " +
                    "Rādījumi no maiņām un degvielas čekiem tiek ņemti vērā automātiski.",
                style = MaterialTheme.typography.bodySmall,
            )
            readings.filter { it.dateMs in Period.of(year, null) }.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fmtDate(r.dateMs)}  ${r.note}", Modifier.weight(1f))
                    Text("${fmtNum(r.value, 0)} km", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editingReading = r }) { Text("Labot") }
                }
            }
        }

        SectionCard {
            Text("Kā tiek rēķināts", style = MaterialTheme.typography.titleMedium)
            Text(
                "Darba īpatsvars = darba km ÷ kopējais nobraukums. Attiecināmie izdevumi = izdevumi, kas atzīmēti kā 100% darbam, " +
                    "plus pārējie izdevumi × īpatsvars. Šo summu var izmantot saimnieciskās darbības izdevumu uzskaitē " +
                    "(ieņēmumu un izdevumu žurnālā un gada deklarācijā).\n\n" +
                    "Šī ir palīgierīce, nevis nodokļu konsultācija. Pārbaudiet aktuālās VID prasības " +
                    "(piem., vai auto izmantošanai saimnieciskajā darbībā piemērojams UVTN) un glabājiet čekus.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    editingReading?.let { r ->
        var date by remember(r) { mutableLongStateOf(r.dateMs) }
        var value by remember(r) { mutableStateOf(if (r.value > 0) fmtNum(r.value, 0) else "") }
        var note by remember(r) { mutableStateOf(r.note) }
        FormDialog(
            title = "Odometra rādījums",
            onDismiss = { editingReading = null },
            saveEnabled = (parseNum(value) ?: 0.0) > 0,
            onSave = {
                Repo.saveReading(r.copy(dateMs = date, value = parseNum(value)!!, note = note.trim()))
                editingReading = null
            },
            onDelete = if (r.id != 0L) ({ Repo.deleteReading(r.id); editingReading = null }) else null,
        ) {
            DateTimeRow("Datums", date, { date = it })
            NumIn("Rādījums", value, { value = it }, suffix = "km")
            TextIn("Piezīme", note, { note = it }, placeholder = "Gada sākums")
        }
    }
}

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val s = Repo.settings.value
    var driver by remember { mutableStateOf(s.driver) }
    var carReg by remember { mutableStateOf(s.carReg) }
    var carModel by remember { mutableStateOf(s.carModel) }
    var fuel by remember { mutableStateOf(s.fuel) }
    var platforms by remember { mutableStateOf(s.platforms.joinToString(", ")) }
    var purpose by remember { mutableStateOf(s.purpose) }
    var stopMin by remember { mutableStateOf(s.autoStopMin.toString()) }
    FormDialog(
        title = "Iestatījumi",
        onDismiss = onClose,
        onSave = {
            Repo.saveSettings(
                s.copy(
                    driver = driver.trim(), carReg = carReg.trim().uppercase(), carModel = carModel.trim(),
                    fuel = fuel.trim(),
                    platforms = platforms.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    purpose = purpose.trim(),
                    autoStopMin = stopMin.toIntOrNull()?.coerceIn(2, 120) ?: s.autoStopMin,
                )
            )
            onClose()
        },
    ) {
        Text("Šie dati parādās ceļazīmju galvenē.", style = MaterialTheme.typography.bodyMedium)
        TextIn("Vadītājs (vārds, uzvārds)", driver, { driver = it })
        TextIn("Reģistrācijas numurs", carReg, { carReg = it }, placeholder = "AB-1234")
        TextIn("Marka, modelis", carModel, { carModel = it }, placeholder = "Toyota Corolla")
        TextIn("Degvielas veids", fuel, { fuel = it })
        TextIn("Platformas (atdalītas ar komatu)", platforms, { platforms = it })
        TextIn("Noklusējuma brauciena mērķis", purpose, { purpose = it }, singleLine = false)
        NumIn("Automātiskais brauciens beidzas pēc stāvēšanas", stopMin, { stopMin = it.filter(Char::isDigit) }, suffix = "min")
        Text(
            "Īsāks laiks – katrs brauciens starp adresēm atsevišķi. Garāks (piem. 30–60 min) – visa maiņa kā viens brauciens, " +
                "arī gaidot pie restorāna.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
