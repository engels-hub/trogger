package lv.papart.trogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import lv.papart.trogger.Mode
import lv.papart.trogger.Period
import lv.papart.trogger.Repo
import lv.papart.trogger.TrackingService
import lv.papart.trogger.Trip
import lv.papart.trogger.fmtKm
import lv.papart.trogger.fmtNum
import lv.papart.trogger.fmtTime
import lv.papart.trogger.parseNum
import lv.papart.trogger.summarize
import java.util.Calendar
import kotlin.math.roundToLong

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftScreen() {
    val ctx = LocalContext.current
    val trips by Repo.trips.collectAsState()
    val expenses by Repo.expenses.collectAsState()
    val readings by Repo.readings.collectAsState()
    val settings by Repo.settings.collectAsState()
    val live by TrackingService.live.collectAsState()
    val active = trips.firstOrNull { it.active }

    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LifecycleResumeEffect(Unit) {
        batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose { }
    }
    LaunchedEffect(active?.id) {
        while (active != null) { now = System.currentTimeMillis(); delay(1000) }
    }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var bgLocation by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        bgLocation = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        onPauseOrDispose { }
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bgLocation = it }
    val hasLocation = {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    var pendingAfterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) pendingAfterPerm?.invoke()
        pendingAfterPerm = null
    }
    fun withLocation(action: () -> Unit) {
        if (hasLocation()) action() else { pendingAfterPerm = action; permLauncher.launch(perms.toTypedArray()) }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!batteryOk) {
            SectionCard {
                Text("Atļaujiet darboties fonā", fontWeight = FontWeight.Bold)
                Text(
                    "Lai GPS uzskaite neapstātos ar izslēgtu ekrānu, izslēdziet akumulatora optimizāciju šai lietotnei. " +
                        "Xiaomi tālruņos arī: Iestatījumi → Lietotnes → trogger → Akumulators → Bez ierobežojumiem, un ieslēdziet Autostart.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Atļaut") }
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automātiskā uzskaite", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !settings.autoEnabled -> "Brauciens sāksies pats, kad sāksiet braukt ar auto ātrumu."
                            live == null -> "Apturēta – atveriet lietotni vai pārslēdziet."
                            live!!.mode == Mode.CONFIRM -> "Pārbauda ātrumu… ${live!!.speedKmh.toInt()} km/h"
                            live!!.mode == Mode.WATCH -> "Gaida braukšanu"
                            else -> "Ieslēgta"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.autoEnabled, onCheckedChange = { on ->
                    if (on) withLocation {
                        Repo.saveSettings(Repo.settings.value.copy(autoEnabled = true))
                        TrackingService.sync(ctx)
                    } else {
                        Repo.saveSettings(Repo.settings.value.copy(autoEnabled = false))
                        TrackingService.sync(ctx)
                    }
                })
            }
            if (settings.autoEnabled && !bgLocation) {
                Text(
                    "Lai uzskaite atsāktos pati pēc tālruņa restartēšanas, atļaujiet atrašanās vietu \"Vienmēr\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Atļaut vienmēr") }
            }
        }

        if (active == null) {
            SectionCard {
                Text("Nav aktīva brauciena", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sāciet maiņu pirms pirmā pasūtījuma. Lietotne skaitīs kilometrus pēc GPS arī ar izslēgtu ekrānu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { withLocation { showStart = true } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("  Sākt maiņu", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            val km = if (live?.tripId == active.id) live!!.km else active.gpsKm
            val tracking = live?.tripId == active.id
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (active.auto) "Brauciens (automātiski)" else "Maiņa aktīva", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Icon(
                        if (tracking && live!!.gpsOk) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = null,
                        tint = if (tracking && live!!.gpsOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "${fmtKm(km)} km",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (tracking) StatRow("Ātrums", "${live!!.speedKmh.toInt()} km/h")
                StatRow("Sākts", fmtTime(active.startMs))
                StatRow("Ilgums", duration(now - active.startMs))
                if (active.platform.isNotEmpty()) StatRow("Platforma", active.platform)
                active.odoStart?.let { StatRow("Odometrs sākumā", "${fmtNum(it, 0)} km") }
                if (active.auto) Text(
                    "Beigsies pats pēc ${settings.autoStopMin} min stāvēšanas.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!tracking) {
                    Text(
                        "GPS uzskaite ir apturēta (sistēma aizvēra lietotni). Nospiediet, lai turpinātu.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { withLocation { TrackingService.sync(ctx) } }) { Text("Turpināt uzskaiti") }
                }
                Button(
                    onClick = { showEnd = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.Stop, null)
                    Text(if (active.auto) "  Beigt braucienu" else "  Beigt maiņu", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        val cal = Calendar.getInstance()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayKm = trips.filter { !it.active && it.business && it.startMs >= today }.sumOf { it.km }
        val month = summarize(Period.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)), trips, expenses, readings)
        val year = summarize(Period.of(cal.get(Calendar.YEAR), null), trips, expenses, readings)
        SectionCard {
            Text("Darba kilometri", style = MaterialTheme.typography.titleMedium)
            StatRow("Šodien", "${fmtKm(todayKm)} km")
            StatRow("${month.period.label} (${month.shifts} maiņas)", "${fmtKm(month.businessKm)} km")
            StatRow("Gadā", "${fmtKm(year.businessKm)} km")
            StatRow("Darba īpatsvars gadā", "${fmtKm(year.businessShare * 100)} %", strong = true)
        }
    }

    if (showStart) {
        var odo by remember { mutableStateOf(Repo.lastOdometer()?.roundToLong()?.toString() ?: "") }
        var platform by remember { mutableStateOf(settings.platforms.firstOrNull() ?: "") }
        AlertDialog(
            onDismissRequest = { showStart = false },
            title = { Text("Sākt maiņu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumIn("Odometra rādījums", odo, { odo = it }, suffix = "km")
                    Text("Platforma", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.platforms.forEach { p ->
                            FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Repo.saveTrip(
                        Trip(
                            startMs = System.currentTimeMillis(), odoStart = parseNum(odo),
                            platform = platform, purpose = settings.purpose, business = true, active = true,
                        )
                    )
                    TrackingService.sync(ctx)
                    showStart = false
                }) { Text("Sākt") }
            },
            dismissButton = { TextButton(onClick = { showStart = false }) { Text("Atcelt") } },
        )
    }

    if (showEnd && active != null) {
        val gpsKm = if (live?.tripId == active.id) live!!.km else active.gpsKm
        val lastRoute = trips.firstOrNull { !it.active && it.business && it.route.isNotEmpty() }?.route ?: ""
        var odo by remember {
            mutableStateOf(active.odoStart?.let { (it + gpsKm).roundToLong().toString() } ?: "")
        }
        var route by remember { mutableStateOf(lastRoute) }
        var note by remember { mutableStateOf("") }
        var confirmDiscard by remember { mutableStateOf(false) }
        FormDialog(
            title = if (active.auto) "Beigt braucienu" else "Beigt maiņu",
            onDismiss = { showEnd = false },
            onSave = {
                Repo.saveTrip(
                    active.copy(
                        endMs = System.currentTimeMillis(), odoEnd = parseNum(odo), gpsKm = gpsKm,
                        route = route.trim(), note = note.trim(), active = false,
                    )
                )
                TrackingService.sync(ctx)
                showEnd = false
            },
        ) {
            StatRow("GPS nobraukums", "${fmtKm(gpsKm)} km", strong = true)
            NumIn("Odometra rādījums beigās", odo, { odo = it }, suffix = "km")
            Text(
                "Ieteikums aprēķināts no sākuma rādījuma un GPS. Ierakstiet faktisko rādījumu no paneļa – ceļazīmē tiek izmantots odometrs.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextIn("Maršruts", route, { route = it }, placeholder = "Rīga – piegādes pa pilsētu")
            TextIn("Piezīmes", note, { note = it }, singleLine = false)
            TextButton(onClick = { confirmDiscard = true }) {
                Text("Atmest maiņu (nesaglabāt)", color = MaterialTheme.colorScheme.error)
            }
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Atmest maiņu?") },
                text = { Text("Maiņa un tās GPS dati tiks dzēsti.") },
                confirmButton = {
                    TextButton(onClick = {
                        Repo.deleteTrip(active.id)
                        TrackingService.sync(ctx)
                        confirmDiscard = false; showEnd = false
                    }) { Text("Atmest") }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Atcelt") } },
            )
        }
    }
}

private fun duration(ms: Long): String {
    val m = (ms / 60000).coerceAtLeast(0)
    return "${m / 60} h ${m % 60} min"
}
