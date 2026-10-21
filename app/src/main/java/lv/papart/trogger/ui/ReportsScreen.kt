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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import lv.papart.trogger.LANGUAGES
import lv.papart.trogger.TrackingService
import lv.papart.trogger.categoryLabel
import lv.papart.trogger.monthName
import lv.papart.trogger.uiLabel
import lv.papart.trogger.OdoReading
import lv.papart.trogger.Period
import lv.papart.trogger.Repo
import lv.papart.trogger.fmtDate
import lv.papart.trogger.fmtEur
import lv.papart.trogger.fmtKm
import lv.papart.trogger.fmtNum
import lv.papart.trogger.parseNum
import lv.papart.trogger.summarize
import lv.papart.trogger.kmUnit
import lv.papart.trogger.tr
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
        Toast.makeText(ctx, if (ok) tr("Fails saglabāts", "Файл сохранён") else tr("Neizdevās saglabāt failu", "Не удалось сохранить файл"), Toast.LENGTH_SHORT).show()

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
            IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Iepriekšējais gads", "Предыдущий год")) }
            Text("$year", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Nākamais gads", "Следующий год")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = month == null, onClick = { month = null }, label = { Text(tr("Viss gads", "Весь год")) })
            (0..11).forEach { i ->
                FilterChip(selected = month == i, onClick = { month = i }, label = { Text(monthName(i).take(3)) })
            }
        }

        SectionCard {
            Text(period.uiLabel(), style = MaterialTheme.typography.titleMedium)
            StatRow(tr("Kopējais nobraukums", "Общий пробег"), "${fmtKm(s.totalKm)} ${kmUnit()}")
            StatRow(tr("Darba braucieni (${s.shifts})", "Рабочие поездки (${s.shifts})"), "${fmtKm(s.businessKm)} ${kmUnit()}")
            StatRow(tr("Privātie braucieni", "Личные поездки"), "${fmtKm(s.privateKm)} ${kmUnit()}")
            StatRow(tr("Darba īpatsvars", "Доля рабочих поездок"), "${fmtKm(s.businessShare * 100)} %", strong = true)
            if (s.odometerKm == null) {
                Text(
                    tr("Nav odometra rādījumu pirms un perioda beigās, tāpēc privātie km ir tikai reģistrētie braucieni. ", "Нет показаний одометра до и в конце периода, поэтому личные км – только записанные поездки. ") +
                        tr("Pievienojiet rādījumu perioda sākumā (piem. 1. janvārī), lai īpatsvars būtu precīzs.", "Добавьте показание на начало периода (напр. 1 января), чтобы доля была точной."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard {
            Text(tr("Izdevumi", "Расходы"), style = MaterialTheme.typography.titleMedium)
            EXPENSE_CATEGORIES.forEach { c -> s.byCategory[c]?.let { StatRow(categoryLabel(c), fmtEur(it)) } }
            if (s.fuelLiters > 0) StatRow(categoryLabel("Degviela"), "${fmtNum(s.fuelLiters, 2)} " + tr("l", "л"))
            StatRow(tr("Kopā", "Итого"), fmtEur(s.expensesTotal))
            StatRow(tr("Attiecināmie (${fmtKm(s.businessShare * 100)} % + 100% izdevumi)", "Вычитаемые (${fmtKm(s.businessShare * 100)} % + расходы 100%)"), fmtEur(s.deductible), strong = true)
        }

        SectionCard {
            Text(tr("Eksports", "Экспорт") + " – ${period.uiLabel()}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { xlsx.launch("$fileBase.xlsx") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, null); Text(tr("  Excel (ceļazīmes, izdevumi, kopsavilkums)", "  Excel (путевые листы, расходы, сводка)"))
            }
            OutlinedButton(onClick = { tripsCsv.launch("${fileBase}_braucieni.csv") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, null); Text(tr("  CSV – braucieni", "  CSV – поездки"))
            }
            OutlinedButton(onClick = { expCsv.launch("${fileBase}_izdevumi.csv") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, null); Text(tr("  CSV – izdevumi", "  CSV – расходы"))
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Odometra rādījumi", "Показания одометра"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val start = Calendar.getInstance().apply { clear(); set(year, 0, 1) }.timeInMillis
                    editingReading = OdoReading(
                        dateMs = if (readings.none { it.dateMs in Period.of(year, null) }) start else System.currentTimeMillis(),
                        value = 0.0,
                    )
                }) { Icon(Icons.Default.Add, tr("Pievienot rādījumu", "Добавить показание")) }
            }
            Text(
                tr("Rādījumi gada sākumā un beigās nosaka kopējo nobraukumu, tātad arī privāto km daļu. ", "Показания на начало и конец года задают общий пробег, а значит и долю личных км. ") +
                    tr("Rādījumi no maiņām un degvielas čekiem tiek ņemti vērā automātiski.", "Показания из смен и чеков за топливо учитываются автоматически."),
                style = MaterialTheme.typography.bodySmall,
            )
            readings.filter { it.dateMs in Period.of(year, null) }.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fmtDate(r.dateMs)}  ${r.note}", Modifier.weight(1f))
                    Text("${fmtNum(r.value, 0)} ${kmUnit()}", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editingReading = r }) { Text(tr("Labot", "Изменить")) }
                }
            }
        }

        SectionCard {
            Text(tr("Kā tiek rēķināts", "Как считается"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Darba īpatsvars = darba km ÷ kopējais nobraukums. Attiecināmie izdevumi = izdevumi, kas atzīmēti kā 100% darbam, ", "Доля рабочих поездок = рабочие км ÷ общий пробег. Вычитаемые расходы = расходы, отмеченные как 100% для работы, ") +
                    tr("plus pārējie izdevumi × īpatsvars. Šo summu var izmantot saimnieciskās darbības izdevumu uzskaitē ", "плюс остальные расходы × доля. Эту сумму можно использовать в учёте расходов хозяйственной деятельности ") +
                    tr("(ieņēmumu un izdevumu žurnālā un gada deklarācijā).\n\n", "(журнал доходов и расходов и годовая декларация).\n\n") +
                    tr("Šī ir palīgierīce, nevis nodokļu konsultācija. Pārbaudiet aktuālās VID prasības ", "Это вспомогательный инструмент, а не налоговая консультация. Проверяйте актуальные требования VID ") +
                    tr("(piem., vai auto izmantošanai saimnieciskajā darbībā piemērojams UVTN) un glabājiet čekus.", "(напр., применяется ли UVTN к использованию авто в хозяйственной деятельности) и храните чеки."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    editingReading?.let { r ->
        var date by remember(r) { mutableLongStateOf(r.dateMs) }
        var value by remember(r) { mutableStateOf(if (r.value > 0) fmtNum(r.value, 0) else "") }
        var note by remember(r) { mutableStateOf(r.note) }
        FormDialog(
            title = tr("Odometra rādījums", "Показание одометра"),
            onDismiss = { editingReading = null },
            saveEnabled = (parseNum(value) ?: 0.0) > 0,
            onSave = {
                Repo.saveReading(r.copy(dateMs = date, value = parseNum(value)!!, note = note.trim()))
                editingReading = null
            },
            onDelete = if (r.id != 0L) ({ Repo.deleteReading(r.id); editingReading = null }) else null,
        ) {
            DateTimeRow(tr("Datums", "Дата"), date, { date = it })
            NumIn(tr("Rādījums", "Показание"), value, { value = it }, suffix = kmUnit())
            TextIn(tr("Piezīme", "Заметка"), note, { note = it }, placeholder = tr("Gada sākums", "Начало года"))
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
    var lang by remember { mutableStateOf(s.lang) }
    val ctx = LocalContext.current
    FormDialog(
        title = tr("Iestatījumi", "Настройки"),
        onDismiss = onClose,
        onSave = {
            Repo.saveSettings(
                s.copy(
                    driver = driver.trim(), carReg = carReg.trim().uppercase(), carModel = carModel.trim(),
                    fuel = fuel.trim(),
                    platforms = platforms.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    purpose = purpose.trim(),
                    autoStopMin = stopMin.toIntOrNull()?.coerceIn(2, 120) ?: s.autoStopMin,
                    lang = lang,
                )
            )
            // Re-post the tracking notification in the new language.
            if (TrackingService.live.value != null) TrackingService.sync(ctx)
            onClose()
        },
    ) {
        Text("Valoda / Язык", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LANGUAGES.forEachIndexed { i, (code, name) ->
                SegmentedButton(lang == code, { lang = code }, SegmentedButtonDefaults.itemShape(i, LANGUAGES.size)) { Text(name) }
            }
        }
        Text(
            tr("Atskaites un eksports vienmēr ir latviešu valodā.", "Отчёты и экспорт всегда на латышском."),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(tr("Šie dati parādās ceļazīmju galvenē.", "Эти данные указываются в шапке путевых листов."), style = MaterialTheme.typography.bodyMedium)
        TextIn(tr("Vadītājs (vārds, uzvārds)", "Водитель (имя, фамилия)"), driver, { driver = it })
        TextIn(tr("Reģistrācijas numurs", "Регистрационный номер"), carReg, { carReg = it }, placeholder = "AB-1234")
        TextIn(tr("Marka, modelis", "Марка, модель"), carModel, { carModel = it }, placeholder = "Toyota Corolla")
        TextIn(tr("Degvielas veids", "Вид топлива"), fuel, { fuel = it })
        TextIn(tr("Platformas (atdalītas ar komatu)", "Платформы (через запятую)"), platforms, { platforms = it })
        TextIn(tr("Noklusējuma brauciena mērķis", "Цель поездки по умолчанию"), purpose, { purpose = it }, singleLine = false)
        NumIn(tr("Automātiskais brauciens beidzas pēc stāvēšanas", "Автопоездка завершается после стоянки"), stopMin, { stopMin = it.filter(Char::isDigit) }, suffix = tr("min", "мин"))
        Text(
            tr("Īsāks laiks – katrs brauciens starp adresēm atsevišķi. Garāks (piem. 30–60 min) – visa maiņa kā viens brauciens, ", "Короче – каждая поездка между адресами отдельно. Дольше (напр. 30–60 мин) – вся смена одной поездкой, ") +
                tr("arī gaidot pie restorāna.", "даже с ожиданием у ресторана."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
