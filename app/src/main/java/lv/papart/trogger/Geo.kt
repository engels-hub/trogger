package lv.papart.trogger

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns the first and last GPS point of each trip into a street address.
 * Addresses are fetched in Latvian (they go on the ceļazīme) and need a network
 * connection; trips that could not be resolved are retried on the next run.
 */
object Geo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val latvian = Locale.forLanguageTag("lv-LV")

    /** Only retry recent trips, so a permanently failing lookup is not repeated forever. */
    private const val RETRY_DAYS = 60

    private sealed interface Result
    private data class Found(val text: String) : Result
    private data object Failed : Result

    fun fillMissing(context: Context) {
        val app = context.applicationContext
        if (!Geocoder.isPresent()) return
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                val since = System.currentTimeMillis() - RETRY_DAYS * 24 * 3600 * 1000L
                var changed = false
                for (t in Repo.db.trips().filter { it.startMs >= since }) {
                    if (t.startAddr.isEmpty()) changed = resolveInto(app, t.id, start = true) || changed
                    if (!t.active && t.endAddr.isEmpty()) changed = resolveInto(app, t.id, start = false) || changed
                }
                if (changed) launch(Dispatchers.Main) { Repo.refresh() }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun resolveInto(context: Context, tripId: Long, start: Boolean): Boolean {
        val (lat, lon) = Repo.db.endPoint(tripId, first = start) ?: return false
        val text = when (val r = lookup(context, lat, lon)) {
            is Found -> r.text
            Failed -> return false
        }
        Repo.db.setAddress(tripId, start, text)
        return true
    }

    private suspend fun lookup(context: Context, lat: Double, lon: Double): Result {
        val geocoder = Geocoder(context, latvian)
        val addresses: List<Address>? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(list: MutableList<Address>) = cont.resume(list)
                        override fun onError(message: String?) = cont.resume(null)
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)
            }
        } catch (e: Exception) {
            null
        }
        return when {
            addresses == null -> Failed // offline or service error: try again later
            addresses.isEmpty() -> Found(coords(lat, lon)) // nowhere to name, keep coordinates
            else -> Found(short(addresses[0]) ?: coords(lat, lon))
        }
    }

    /** "Brīvības iela 100, Rīga" rather than the full line with district, postcode and country. */
    private fun short(a: Address): String? {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null }
        val town = a.locality ?: a.subAdminArea ?: a.adminArea
        return listOfNotNull(street, town).joinToString(", ").ifBlank { null }
            ?: a.getAddressLine(0)
    }

    private fun coords(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)
}
