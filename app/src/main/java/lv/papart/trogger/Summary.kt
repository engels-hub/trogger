package lv.papart.trogger

import java.util.Calendar

/** Half-open time range [from, to) in epoch millis. */
data class Period(val from: Long, val to: Long, val label: String) {
    operator fun contains(ms: Long) = ms in from until to

    companion object {
        /** month is 0-based; null means the whole year. */
        fun of(year: Int, month: Int?): Period {
            val c = Calendar.getInstance().apply { clear() }
            return if (month == null) {
                c.set(year, Calendar.JANUARY, 1)
                val from = c.timeInMillis
                c.add(Calendar.YEAR, 1)
                Period(from, c.timeInMillis, "$year")
            } else {
                c.set(year, month, 1)
                val from = c.timeInMillis
                c.add(Calendar.MONTH, 1)
                Period(from, c.timeInMillis, "${MONTHS_LV[month]} $year")
            }
        }
    }
}

data class Summary(
    val period: Period,
    val businessKm: Double,
    val privateLoggedKm: Double,
    /** Total driven per odometer; null if there are not enough readings around the period. */
    val odometerKm: Double?,
    val shifts: Int,
    val expensesTotal: Double,
    val expensesFull: Double,
    val expensesShared: Double,
    val byCategory: Map<String, Double>,
    val fuelLiters: Double,
) {
    val totalKm: Double get() = maxOf(odometerKm ?: 0.0, businessKm + privateLoggedKm)
    val privateKm: Double get() = (totalKm - businessKm).coerceAtLeast(0.0)
    val businessShare: Double get() = if (totalKm > 0) (businessKm / totalKm).coerceIn(0.0, 1.0) else 0.0
    val deductible: Double get() = expensesFull + expensesShared * businessShare
}

/** Every (time, odometer) pair the user has entered, from any source. */
fun odometerPoints(trips: List<Trip>, expenses: List<Expense>, readings: List<OdoReading>): List<Pair<Long, Double>> {
    val pts = mutableListOf<Pair<Long, Double>>()
    trips.forEach { t ->
        t.odoStart?.let { pts += t.startMs to it }
        if (t.odoEnd != null) pts += (t.endMs ?: t.startMs) to t.odoEnd
    }
    expenses.forEach { e -> e.odo?.let { pts += e.dateMs to it } }
    readings.forEach { r -> pts += r.dateMs to r.value }
    return pts.sortedBy { it.first }
}

fun summarize(
    period: Period,
    trips: List<Trip>,
    expenses: List<Expense>,
    readings: List<OdoReading>,
): Summary {
    val done = trips.filter { !it.active && it.startMs in period }
    val exp = expenses.filter { it.dateMs in period }

    // Odometer: the last reading before the period (or the first inside it) to the last inside it.
    val pts = odometerPoints(trips, expenses, readings)
    val startReading = pts.lastOrNull { it.first < period.from }?.second
        ?: pts.firstOrNull { it.first in period }?.second
    val endReading = pts.lastOrNull { it.first in period }?.second
    val odoKm = if (startReading != null && endReading != null && endReading > startReading)
        endReading - startReading else null

    val full = exp.filter { it.fullyBusiness }.sumOf { it.amount }
    val shared = exp.filter { !it.fullyBusiness }.sumOf { it.amount }

    return Summary(
        period = period,
        businessKm = done.filter { it.business }.sumOf { it.km },
        privateLoggedKm = done.filter { !it.business }.sumOf { it.km },
        odometerKm = odoKm,
        shifts = done.count { it.business },
        expensesTotal = full + shared,
        expensesFull = full,
        expensesShared = shared,
        byCategory = exp.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } },
        fuelLiters = exp.sumOf { it.liters ?: 0.0 },
    )
}
