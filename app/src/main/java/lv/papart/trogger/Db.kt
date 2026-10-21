package lv.papart.trogger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Trip(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val odoStart: Double? = null,
    val odoEnd: Double? = null,
    val gpsKm: Double = 0.0,
    val route: String = "",
    val purpose: String = "",
    val platform: String = "",
    val business: Boolean = true,
    val note: String = "",
    val active: Boolean = false,
    /** Started by motion detection; ends itself after the car has stood still. */
    val auto: Boolean = false,
) {
    /** Odometer distance is what goes on the ceļazīme; GPS is the fallback. */
    val km: Double
        get() {
            if (odoStart != null && odoEnd != null && odoEnd >= odoStart) return odoEnd - odoStart
            return gpsKm
        }
}

data class Expense(
    val id: Long = 0,
    val dateMs: Long,
    val category: String,
    val amount: Double,
    val liters: Double? = null,
    val odo: Double? = null,
    val vendor: String = "",
    val docNo: String = "",
    val note: String = "",
    /** Wholly business (e.g. parking during a delivery) — not split by business share. */
    val fullyBusiness: Boolean = false,
)

data class OdoReading(val id: Long = 0, val dateMs: Long, val value: Double, val note: String = "")

val EXPENSE_CATEGORIES = listOf(
    "Degviela",
    "Remonts un apkope",
    "Riepas",
    "OCTA",
    "KASKO",
    "Tehniskā apskate",
    "Ekspluatācijas nodoklis",
    "Stāvvieta",
    "Mazgāšana",
    "Cits",
)

class Db(context: Context) : SQLiteOpenHelper(context, "trogger.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ms INTEGER NOT NULL, end_ms INTEGER,
                odo_start REAL, odo_end REAL, gps_km REAL NOT NULL DEFAULT 0,
                route TEXT NOT NULL DEFAULT '', purpose TEXT NOT NULL DEFAULT '',
                platform TEXT NOT NULL DEFAULT '', business INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 0,
                auto INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE expenses(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_ms INTEGER NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL,
                liters REAL, odo REAL, vendor TEXT NOT NULL DEFAULT '',
                doc_no TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
                fully_business INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE odo_readings(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_ms INTEGER NOT NULL, value REAL NOT NULL, note TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE track_points(
                trip_id INTEGER NOT NULL, t INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, acc REAL NOT NULL)"""
        )
        db.execSQL("CREATE INDEX track_trip ON track_points(trip_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- trips ----

    fun trips(): List<Trip> = readableDatabase
        .rawQuery("SELECT * FROM trips ORDER BY start_ms DESC", null)
        .use { c -> generateSequence { if (c.moveToNext()) c.toTrip() else null }.toList() }

    fun activeTrip(): Trip? = readableDatabase
        .rawQuery("SELECT * FROM trips WHERE active = 1 LIMIT 1", null)
        .use { c -> if (c.moveToFirst()) c.toTrip() else null }

    fun trip(id: Long): Trip? = readableDatabase
        .rawQuery("SELECT * FROM trips WHERE id = ?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) c.toTrip() else null }

    fun saveTrip(t: Trip): Long {
        val v = ContentValues().apply {
            put("start_ms", t.startMs); put("end_ms", t.endMs)
            put("odo_start", t.odoStart); put("odo_end", t.odoEnd); put("gps_km", t.gpsKm)
            put("route", t.route); put("purpose", t.purpose); put("platform", t.platform)
            put("business", if (t.business) 1 else 0); put("note", t.note)
            put("active", if (t.active) 1 else 0); put("auto", if (t.auto) 1 else 0)
        }
        val db = writableDatabase
        return if (t.id == 0L) db.insert("trips", null, v)
        else { db.update("trips", v, "id = ?", arrayOf(t.id.toString())); t.id }
    }

    fun updateGpsKm(id: Long, km: Double) {
        writableDatabase.execSQL("UPDATE trips SET gps_km = ? WHERE id = ?", arrayOf<Any>(km, id))
    }

    fun deleteTrip(id: Long) {
        writableDatabase.delete("track_points", "trip_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
    }

    fun addPoint(tripId: Long, t: Long, lat: Double, lon: Double, acc: Float) {
        writableDatabase.insert("track_points", null, ContentValues().apply {
            put("trip_id", tripId); put("t", t); put("lat", lat); put("lon", lon); put("acc", acc)
        })
    }

    /** Last accepted point of a trip, so a restarted service continues the distance. */
    fun lastPoint(tripId: Long): Triple<Double, Double, Long>? = readableDatabase
        .rawQuery(
            "SELECT lat, lon, t FROM track_points WHERE trip_id = ? ORDER BY t DESC LIMIT 1",
            arrayOf(tripId.toString())
        ).use { c -> if (c.moveToFirst()) Triple(c.getDouble(0), c.getDouble(1), c.getLong(2)) else null }

    // ---- expenses ----

    fun expenses(): List<Expense> = readableDatabase
        .rawQuery("SELECT * FROM expenses ORDER BY date_ms DESC", null)
        .use { c ->
            generateSequence {
                if (!c.moveToNext()) null else Expense(
                    id = c.long("id"), dateMs = c.long("date_ms"), category = c.str("category"),
                    amount = c.double("amount"), liters = c.doubleOrNull("liters"),
                    odo = c.doubleOrNull("odo"), vendor = c.str("vendor"), docNo = c.str("doc_no"),
                    note = c.str("note"), fullyBusiness = c.long("fully_business") == 1L,
                )
            }.toList()
        }

    fun saveExpense(e: Expense) {
        val v = ContentValues().apply {
            put("date_ms", e.dateMs); put("category", e.category); put("amount", e.amount)
            put("liters", e.liters); put("odo", e.odo); put("vendor", e.vendor)
            put("doc_no", e.docNo); put("note", e.note)
            put("fully_business", if (e.fullyBusiness) 1 else 0)
        }
        if (e.id == 0L) writableDatabase.insert("expenses", null, v)
        else writableDatabase.update("expenses", v, "id = ?", arrayOf(e.id.toString()))
    }

    fun deleteExpense(id: Long) {
        writableDatabase.delete("expenses", "id = ?", arrayOf(id.toString()))
    }

    // ---- odometer readings ----

    fun readings(): List<OdoReading> = readableDatabase
        .rawQuery("SELECT * FROM odo_readings ORDER BY date_ms DESC", null)
        .use { c ->
            generateSequence {
                if (!c.moveToNext()) null
                else OdoReading(c.long("id"), c.long("date_ms"), c.double("value"), c.str("note"))
            }.toList()
        }

    fun saveReading(r: OdoReading) {
        val v = ContentValues().apply {
            put("date_ms", r.dateMs); put("value", r.value); put("note", r.note)
        }
        if (r.id == 0L) writableDatabase.insert("odo_readings", null, v)
        else writableDatabase.update("odo_readings", v, "id = ?", arrayOf(r.id.toString()))
    }

    fun deleteReading(id: Long) {
        writableDatabase.delete("odo_readings", "id = ?", arrayOf(id.toString()))
    }

    private fun Cursor.toTrip() = Trip(
        id = long("id"), startMs = long("start_ms"), endMs = longOrNull("end_ms"),
        odoStart = doubleOrNull("odo_start"), odoEnd = doubleOrNull("odo_end"),
        gpsKm = double("gps_km"), route = str("route"), purpose = str("purpose"),
        platform = str("platform"), business = long("business") == 1L, note = str("note"),
        active = long("active") == 1L, auto = long("auto") == 1L,
    )

    private fun Cursor.idx(n: String) = getColumnIndexOrThrow(n)
    private fun Cursor.long(n: String) = getLong(idx(n))
    private fun Cursor.longOrNull(n: String) = idx(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.double(n: String) = getDouble(idx(n))
    private fun Cursor.doubleOrNull(n: String) = idx(n).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.str(n: String) = getString(idx(n)) ?: ""
}
