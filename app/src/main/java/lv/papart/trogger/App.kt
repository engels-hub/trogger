package lv.papart.trogger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TrackingService.CHANNEL, "Aktīvā maiņa", NotificationManager.IMPORTANCE_LOW)
        )
    }
}

data class Settings(
    val driver: String = "",
    val carReg: String = "",
    val carModel: String = "",
    val fuel: String = "Benzīns",
    val platforms: List<String> = listOf("Wolt", "Bolt Food"),
    val purpose: String = "Kurjera pakalpojumi – preču piegāde",
    val autoEnabled: Boolean = false,
    /** Minutes standing still before an auto trip is closed. Couriers wait at restaurants, so not too short. */
    val autoStopMin: Int = 10,
)

/** Single source of truth for the UI; every write goes through here and refreshes the flows. */
object Repo {
    lateinit var db: Db
        private set
    private lateinit var appContext: Context

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses
    private val _readings = MutableStateFlow<List<OdoReading>>(emptyList())
    val readings: StateFlow<List<OdoReading>> = _readings
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    fun init(context: Context) {
        appContext = context.applicationContext
        db = Db(appContext)
        _settings.value = loadSettings()
        refresh()
    }

    fun refresh() {
        _trips.value = db.trips()
        _expenses.value = db.expenses()
        _readings.value = db.readings()
    }

    fun saveTrip(t: Trip): Long = db.saveTrip(t).also { refresh() }
    fun deleteTrip(id: Long) { db.deleteTrip(id); refresh() }
    fun saveExpense(e: Expense) { db.saveExpense(e); refresh() }
    fun deleteExpense(id: Long) { db.deleteExpense(id); refresh() }
    fun saveReading(r: OdoReading) { db.saveReading(r); refresh() }
    fun deleteReading(id: Long) { db.deleteReading(id); refresh() }

    /** Highest odometer value seen anywhere — the default for the next shift's start. */
    fun lastOdometer(): Double? = odometerPoints(_trips.value, _expenses.value, _readings.value)
        .maxOfOrNull { it.second }

    private fun prefs() = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun loadSettings(): Settings {
        val p = prefs()
        val d = Settings()
        return Settings(
            driver = p.getString("driver", d.driver)!!,
            carReg = p.getString("carReg", d.carReg)!!,
            carModel = p.getString("carModel", d.carModel)!!,
            fuel = p.getString("fuel", d.fuel)!!,
            platforms = p.getString("platforms", null)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.platforms,
            purpose = p.getString("purpose", d.purpose)!!,
            autoEnabled = p.getBoolean("autoEnabled", d.autoEnabled),
            autoStopMin = p.getInt("autoStopMin", d.autoStopMin),
        )
    }

    fun saveSettings(s: Settings) {
        prefs().edit()
            .putString("driver", s.driver).putString("carReg", s.carReg)
            .putString("carModel", s.carModel).putString("fuel", s.fuel)
            .putString("platforms", s.platforms.joinToString(","))
            .putString("purpose", s.purpose)
            .putBoolean("autoEnabled", s.autoEnabled)
            .putInt("autoStopMin", s.autoStopMin)
            .apply()
        _settings.value = s
    }
}

// ---- formatting (Latvian conventions: dd.MM.yyyy, decimal comma) ----

private val lv = Locale.forLanguageTag("lv-LV")
fun fmtDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy", lv).format(ms)
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", lv).format(ms)
fun fmtKm(km: Double): String = String.format(lv, "%.1f", km)
fun fmtEur(v: Double): String = String.format(lv, "%.2f €", v)
fun fmtNum(v: Double, decimals: Int = 1): String = String.format(lv, "%.${decimals}f", v)
fun parseNum(s: String): Double? = s.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

val MONTHS_LV = listOf(
    "Janvāris", "Februāris", "Marts", "Aprīlis", "Maijs", "Jūnijs",
    "Jūlijs", "Augusts", "Septembris", "Oktobris", "Novembris", "Decembris",
)
