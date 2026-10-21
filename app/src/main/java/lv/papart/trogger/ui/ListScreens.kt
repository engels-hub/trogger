package lv.papart.trogger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lv.papart.trogger.EXPENSE_CATEGORIES
import lv.papart.trogger.Expense
import lv.papart.trogger.MONTHS_LV
import lv.papart.trogger.Repo
import lv.papart.trogger.Trip
import lv.papart.trogger.fmtDate
import lv.papart.trogger.fmtEur
import lv.papart.trogger.fmtKm
import lv.papart.trogger.fmtNum
import lv.papart.trogger.fmtTime
import lv.papart.trogger.parseNum
import java.util.Calendar

private fun monthKey(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "${MONTHS_LV[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

@Composable
private fun MonthHeader(text: String, total: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text(total, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

// ---------------- trips ----------------

@Composable
fun TripsScreen() {
    val trips by Repo.trips.collectAsState()
    var editing by remember { mutableStateOf<Trip?>(null) }
    val done = trips.filter { !it.active }

    Box(Modifier.fillMaxSize()) {
        if (done.isEmpty()) {
            Text(
                "Vēl nav braucienu. Sāciet maiņu vai pievienojiet braucienu manuāli (arī privātos).",
                modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            done.groupBy { monthKey(it.startMs) }.forEach { (month, list) ->
                item(key = "h$month") {
                    MonthHeader(month, "${fmtKm(list.filter { it.business }.sumOf { it.km })} km darba")
                }
                items(list, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = t }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${fmtDate(t.startMs)}  ${fmtTime(t.startMs)}–${t.endMs?.let { fmtTime(it) } ?: ""}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                listOf(if (t.business) t.platform else "Privāts", if (t.auto) "auto" else "", t.route).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.business) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("${fmtKm(t.km)} km", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                val now = System.currentTimeMillis()
                editing = Trip(startMs = now - 3600_000, endMs = now, purpose = Repo.settings.value.purpose)
            },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Brauciens") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { TripDialog(it) { editing = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripDialog(trip: Trip, onClose: () -> Unit) {
    val settings by Repo.settings.collectAsState()
    var start by remember { mutableLongStateOf(trip.startMs) }
    var end by remember { mutableLongStateOf(trip.endMs ?: trip.startMs) }
    var odoStart by remember { mutableStateOf(numText(trip.odoStart)) }
    var odoEnd by remember { mutableStateOf(numText(trip.odoEnd)) }
    var gps by remember { mutableStateOf(if (trip.gpsKm > 0) fmtNum(trip.gpsKm) else "") }
    var route by remember { mutableStateOf(trip.route) }
    var purpose by remember { mutableStateOf(trip.purpose) }
    var platform by remember { mutableStateOf(trip.platform) }
    var business by remember { mutableStateOf(trip.business) }
    var note by remember { mutableStateOf(trip.note) }

    val os = parseNum(odoStart); val oe = parseNum(odoEnd)
    val odoBad = os != null && oe != null && oe < os

    FormDialog(
        title = if (trip.id == 0L) "Jauns brauciens" else "Brauciens",
        onDismiss = onClose,
        saveEnabled = !odoBad && end >= start,
        onSave = {
            Repo.saveTrip(
                trip.copy(
                    startMs = start, endMs = end, odoStart = os, odoEnd = oe,
                    gpsKm = parseNum(gps) ?: 0.0, route = route.trim(),
                    purpose = if (business) purpose.trim() else purpose.trim().ifEmpty { "Privāts brauciens" },
                    platform = if (business) platform else "", business = business, note = note.trim(),
                )
            )
            onClose()
        },
        onDelete = if (trip.id != 0L) ({ Repo.deleteTrip(trip.id); onClose() }) else null,
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(business, { business = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Darba") }
            SegmentedButton(!business, { business = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Privāts") }
        }
        DateTimeRow("Sākums", start, { start = it; if (end < it) end = it })
        DateTimeRow("Beigas", end, { end = it })
        Row {
            NumIn("Odometrs sākumā", odoStart, { odoStart = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumIn("Odometrs beigās", odoEnd, { odoEnd = it }, Modifier.weight(1f))
        }
        if (odoBad) Text("Beigu rādījums ir mazāks par sākuma.", color = MaterialTheme.colorScheme.error)
        if (os != null && oe != null && !odoBad) StatRow("Nobraukts pēc odometra", "${fmtKm(oe - os)} km", strong = true)
        NumIn("GPS kilometri", gps, { gps = it }, suffix = "km")
        TextIn("Maršruts", route, { route = it }, placeholder = "Rīga – piegādes pa pilsētu")
        if (business) {
            TextIn("Brauciena mērķis", purpose, { purpose = it })
            Text("Platforma", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (settings.platforms + listOfNotNull(platform.takeIf { it.isNotEmpty() && it !in settings.platforms })).forEach { p ->
                    FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p) })
                }
            }
        }
        TextIn("Piezīmes", note, { note = it }, singleLine = false)
    }
}

// ---------------- expenses ----------------

@Composable
fun ExpensesScreen() {
    val expenses by Repo.expenses.collectAsState()
    var editing by remember { mutableStateOf<Expense?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (expenses.isEmpty()) {
            Text(
                "Pievienojiet degvielas čekus, remontu, OCTA, apskati u.c. Saglabājiet arī pašus čekus – VID var tos pieprasīt.",
                modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            expenses.groupBy { monthKey(it.dateMs) }.forEach { (month, list) ->
                item(key = "h$month") { MonthHeader(month, fmtEur(list.sumOf { it.amount })) }
                items(list, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = e }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.category, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(
                                    fmtDate(e.dateMs), e.vendor.takeIf { it.isNotEmpty() },
                                    e.liters?.let { "${fmtNum(it, 2)} l" }, "100% darbam".takeIf { e.fullyBusiness },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(fmtEur(e.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Expense(dateMs = System.currentTimeMillis(), category = EXPENSE_CATEGORIES[0], amount = 0.0) },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Izdevums") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { ExpenseDialog(it) { editing = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseDialog(e: Expense, onClose: () -> Unit) {
    var date by remember { mutableLongStateOf(e.dateMs) }
    var category by remember { mutableStateOf(e.category) }
    var amount by remember { mutableStateOf(if (e.amount > 0) fmtNum(e.amount, 2) else "") }
    var liters by remember { mutableStateOf(numText(e.liters, 2)) }
    var odo by remember { mutableStateOf(numText(e.odo)) }
    var vendor by remember { mutableStateOf(e.vendor) }
    var docNo by remember { mutableStateOf(e.docNo) }
    var note by remember { mutableStateOf(e.note) }
    var full by remember { mutableStateOf(e.fullyBusiness) }
    val amt = parseNum(amount)

    FormDialog(
        title = if (e.id == 0L) "Jauns izdevums" else "Izdevums",
        onDismiss = onClose,
        saveEnabled = amt != null && amt > 0,
        onSave = {
            Repo.saveExpense(
                e.copy(
                    dateMs = date, category = category, amount = amt ?: 0.0,
                    liters = if (category == "Degviela") parseNum(liters) else null,
                    odo = parseNum(odo), vendor = vendor.trim(), docNo = docNo.trim(),
                    note = note.trim(), fullyBusiness = full,
                )
            )
            onClose()
        },
        onDelete = if (e.id != 0L) ({ Repo.deleteExpense(e.id); onClose() }) else null,
    ) {
        DateTimeRow("Datums", date, { date = it }, showTime = false)
        Text("Kategorija", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXPENSE_CATEGORIES.forEach { c ->
                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
            }
        }
        NumIn("Summa", amount, { amount = it }, suffix = "€")
        if (category == "Degviela") NumIn("Litri", liters, { liters = it }, suffix = "l")
        NumIn("Odometra rādījums (nav obligāts)", odo, { odo = it }, suffix = "km")
        TextIn("Pārdevējs", vendor, { vendor = it }, placeholder = "Circle K, Neste, Viada…")
        TextIn("Čeka / rēķina nr.", docNo, { docNo = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(full, { full = it })
            Column {
                Text("100% saimnieciskajai darbībai")
                Text(
                    "Piem., stāvvieta piegādes laikā. Citi izdevumi tiek reizināti ar darba īpatsvaru.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextIn("Piezīmes", note, { note = it }, singleLine = false)
    }
}
