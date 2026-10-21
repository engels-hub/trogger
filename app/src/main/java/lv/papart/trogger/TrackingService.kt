package lv.papart.trogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Mode {
    /** Auto mode, waiting for the motion sensors to report driving. GPS off. */
    WATCH,
    /** Motion says "in vehicle"; GPS on to confirm car speed before opening a trip. */
    CONFIRM,
    /** A trip/shift is open and distance is being recorded. */
    TRACKING,
}

data class LiveShift(
    val mode: Mode,
    val tripId: Long = 0,
    val km: Double = 0.0,
    val speedKmh: Double = 0.0,
    val gpsOk: Boolean = false,
)

/**
 * Foreground service for both manual shifts and automatic trip detection.
 *
 * Auto detection works the way Driversnote's does: the phone's motion sensors
 * (Google Activity Recognition) wake us on "in vehicle", GPS then has to see car
 * speed on consecutive fixes before a trip opens, and the trip closes itself after
 * the car has stood still for the configured number of minutes. Without Google Play
 * services, watch mode falls back to sparse GPS polling for the speed check.
 *
 * The DB row is the durable state; the service can be killed and resumed.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val CHANNEL = "shift"
        private const val NOTIF_ID = 1
        private const val NOTIF_TRIP_SAVED = 2
        const val ACTION_VEHICLE = "lv.papart.trogger.VEHICLE"

        // Fixes worse than this are noise in a city; skip them.
        private const val MAX_ACCURACY_M = 30f
        // Ignore jitter while standing at a restaurant.
        private const val MIN_STEP_M = 15f
        // Anything faster is a GPS jump, not driving.
        private const val MAX_SPEED_MS = 50.0
        // Car speed: faster than cycling or running.
        private const val START_SPEED_MS = 20 / 3.6
        // Consecutive fixes at car speed needed to open a trip.
        private const val START_FIXES = 3
        // Give up confirming if no car speed shows up within this time.
        private const val CONFIRM_TIMEOUT_MS = 4 * 60_000L
        // Without Activity Recognition, poll GPS this often in watch mode.
        private const val FALLBACK_POLL_MS = 20_000L
        // Moving less than this counts as standing still for the auto stop.
        private const val STILL_RADIUS_M = 100f

        private val _live = MutableStateFlow<LiveShift?>(null)
        val live: StateFlow<LiveShift?> = _live

        /** Starts the service, or makes a running one re-read the DB and settings. */
        fun sync(context: Context) {
            val s = Repo.settings.value
            if (Repo.db.activeTrip() == null && !s.autoEnabled) {
                context.stopService(Intent(context, TrackingService::class.java))
            } else {
                context.startForegroundService(Intent(context, TrackingService::class.java))
            }
        }

        fun hasActivityRecognition(context: Context) =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var mode: Mode? = null
    private var gpsIntervalMs = -1L
    private var arRegistered = false

    // tracking
    private var tripId = 0L
    private var tripAuto = false
    private var km = 0.0
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastT = 0L
    private var hasLast = false
    private var speedKmh = 0.0
    private var lastFix: Location? = null
    private var gpsOk = false

    // auto stop: where and when the car last moved beyond STILL_RADIUS_M
    private var anchor: Location? = null
    private var anchorWall = 0L
    private var anchorElapsed = 0L

    // confirm
    private var fastFixes = 0
    private val confirmFixes = mutableListOf<Location>()

    private val tick = object : Runnable {
        override fun run() {
            checkAutoStop()
            handler.postDelayed(this, 30_000)
        }
    }
    private val confirmTimeout = Runnable { if (mode == Mode.CONFIRM) enterWatch() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Restarted from the background without permission to run a location service.
            // Any open shift stays in the DB; opening the app resumes it.
            stopSelf()
            return START_NOT_STICKY
        }

        val trip = Repo.db.activeTrip()
        when {
            trip != null -> if (mode != Mode.TRACKING || tripId != trip.id) enterTracking(trip)
            // The UI ended or discarded the shift.
            mode == Mode.TRACKING -> { tripId = 0; afterTrip() }
            intent?.action == ACTION_VEHICLE -> if (mode == Mode.WATCH) enterConfirm()
            Repo.settings.value.autoEnabled -> if (mode == null) enterWatch()
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    // ---------------- modes ----------------

    private fun enterWatch() {
        mode = Mode.WATCH
        handler.removeCallbacks(confirmTimeout)
        handler.removeCallbacks(tick)
        releaseWakeLock()
        fastFixes = 0; confirmFixes.clear()
        speedKmh = 0.0
        if (registerActivityRecognition()) gps(0) else gps(FALLBACK_POLL_MS)
        publish()
    }

    private fun enterConfirm() {
        mode = Mode.CONFIRM
        fastFixes = 0; confirmFixes.clear()
        acquireWakeLock()
        gps(1000)
        handler.removeCallbacks(confirmTimeout)
        handler.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS)
        publish()
    }

    private fun enterTracking(trip: Trip) {
        mode = Mode.TRACKING
        handler.removeCallbacks(confirmTimeout)
        tripId = trip.id
        tripAuto = trip.auto
        km = trip.gpsKm
        hasLast = false
        Repo.db.lastPoint(trip.id)?.let { (lat, lon, t) ->
            lastLat = lat; lastLon = lon; lastT = t; hasLast = true
        }
        anchor = null
        anchorWall = System.currentTimeMillis()
        anchorElapsed = SystemClock.elapsedRealtime()
        acquireWakeLock()
        gps(2000)
        handler.removeCallbacks(tick)
        if (tripAuto) handler.postDelayed(tick, 30_000)
        publish()
    }

    /** Back to watching if auto mode is on, otherwise done. */
    private fun afterTrip() {
        handler.removeCallbacks(tick)
        tripId = 0
        if (Repo.settings.value.autoEnabled) enterWatch() else stopSelf()
    }

    // ---------------- location ----------------

    @SuppressLint("MissingPermission")
    private fun gps(intervalMs: Long) {
        if (intervalMs == gpsIntervalMs) return
        lm.removeUpdates(this)
        gpsIntervalMs = intervalMs
        if (intervalMs <= 0) return
        try {
            val minDistance = if (mode == Mode.TRACKING) 5f else 0f
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, minDistance, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(loc: Location) {
        gpsOk = loc.hasAccuracy() && loc.accuracy <= MAX_ACCURACY_M
        when (mode) {
            Mode.WATCH, Mode.CONFIRM -> onConfirmFix(loc)
            Mode.TRACKING -> onTrackFix(loc)
            null -> Unit
        }
        publish()
    }

    /** GPS Doppler speed when the chip reports one, else displacement over time. */
    private fun speedOf(loc: Location, prev: Location?): Double {
        if (loc.hasSpeed() && loc.speed > 0f) return loc.speed.toDouble()
        if (prev == null) return 0.0
        val dt = (loc.time - prev.time) / 1000.0
        val d = prev.distanceTo(loc)
        // A displacement inside the combined error circles is jitter, not movement.
        if (dt <= 0 || d <= prev.accuracy + loc.accuracy) return 0.0
        return d / dt
    }

    private fun onConfirmFix(loc: Location) {
        if (!gpsOk) return
        val v = speedOf(loc, confirmFixes.lastOrNull())
        speedKmh = v * 3.6
        if (v >= START_SPEED_MS && v <= MAX_SPEED_MS) {
            // Fallback watch mode: one fast fix is enough to switch to dense GPS.
            if (mode == Mode.WATCH) { enterConfirm(); confirmFixes += loc; fastFixes = 1; return }
            confirmFixes += loc
            if (++fastFixes >= START_FIXES) startAutoTrip()
        } else {
            fastFixes = 0
            confirmFixes.clear()
            confirmFixes += loc
        }
    }

    private fun startAutoTrip() {
        val s = Repo.settings.value
        val first = confirmFixes.first()
        val id = Repo.saveTrip(
            Trip(
                startMs = first.time, platform = s.platforms.firstOrNull() ?: "", purpose = s.purpose,
                business = true, active = true, auto = true,
            )
        )
        val trip = Repo.db.trip(id) ?: return
        val fixes = confirmFixes.toList()
        enterTracking(trip)
        // Count the distance driven while confirming.
        fixes.forEach { onTrackFix(it) }
    }

    private fun onTrackFix(loc: Location) {
        if (!gpsOk) return
        speedKmh = speedOf(loc, lastFix) * 3.6
        lastFix = Location(loc)

        val a = anchor
        if (a == null || a.distanceTo(loc) > STILL_RADIUS_M) {
            anchor = Location(loc)
            anchorWall = loc.time
            anchorElapsed = SystemClock.elapsedRealtime()
        }

        if (!hasLast) {
            accept(loc)
            return
        }
        val d = FloatArray(1)
        Location.distanceBetween(lastLat, lastLon, loc.latitude, loc.longitude, d)
        val step = d[0]
        val dt = (loc.time - lastT).coerceAtLeast(1) / 1000.0
        if (step >= maxOf(MIN_STEP_M, loc.accuracy) && step / dt <= MAX_SPEED_MS) {
            km += step / 1000.0
            accept(loc)
            Repo.db.updateGpsKm(tripId, km)
        }
    }

    private fun accept(loc: Location) {
        lastLat = loc.latitude; lastLon = loc.longitude; lastT = loc.time; hasLast = true
        Repo.db.addPoint(tripId, loc.time, loc.latitude, loc.longitude, loc.accuracy)
    }

    /** Close an auto trip once the car has stood still long enough. Manual shifts never close themselves. */
    private fun checkAutoStop() {
        if (mode != Mode.TRACKING || !tripAuto) return
        val stillMs = SystemClock.elapsedRealtime() - anchorElapsed
        if (stillMs < Repo.settings.value.autoStopMin * 60_000L) return
        val trip = Repo.db.trip(tripId) ?: return afterTrip()
        Repo.saveTrip(trip.copy(endMs = maxOf(anchorWall, trip.startMs), gpsKm = km, active = false))
        if (km >= 0.05) notifySaved(km) else Repo.deleteTrip(trip.id)
        afterTrip()
    }

    // ---------------- activity recognition ----------------

    private fun arIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0, Intent(this, ActivityReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition(): Boolean {
        if (arRegistered) return true
        if (!hasActivityRecognition(this)) return false
        return try {
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), arIntent())
            arRegistered = true
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterActivityRecognition() {
        if (!arRegistered) return
        try {
            ActivityRecognition.getClient(this).removeActivityTransitionUpdates(arIntent())
        } catch (_: SecurityException) {
        }
        arRegistered = false
    }

    // ---------------- notifications & state ----------------

    private fun publish() {
        val m = mode ?: return
        _live.value = LiveShift(m, tripId, km, speedKmh, gpsOk)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (mode) {
            Mode.TRACKING -> (if (tripAuto) "Brauciens (automātiski)" else "Maiņa aktīva") to
                (if (gpsOk) "${fmtKm(km)} km · ${speedKmh.toInt()} km/h" else "${fmtKm(km)} km · gaida GPS signālu")
            Mode.CONFIRM -> "Pārbauda braukšanu…" to "${speedKmh.toInt()} km/h"
            else -> "Automātiskā uzskaite" to "Gaida, kad sāksiet braukt"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifySaved(km: Double) {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_TRIP_SAVED,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Brauciens saglabāts")
                .setContentText("${fmtKm(km)} km · pārbaudiet un ierakstiet odometru")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trogger:track")
            .apply { acquire(16 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @Deprecated("Required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { gpsOk = false; publish() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lm.removeUpdates(this)
        unregisterActivityRecognition()
        if (mode == Mode.TRACKING && tripId != 0L && Repo.db.trip(tripId)?.active == true) {
            Repo.db.updateGpsKm(tripId, km)
        }
        releaseWakeLock()
        _live.value = null
        Repo.refresh()
        super.onDestroy()
    }
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val driving = if (debuggable && intent.getBooleanExtra("debug_vehicle", false)) true else {
            if (!ActivityTransitionResult.hasResult(intent)) return
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            result.transitionEvents.any {
                it.activityType == DetectedActivity.IN_VEHICLE &&
                    it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            }
        }
        if (!driving) return
        try {
            // The service is already in the foreground in auto mode, so this is allowed from the background.
            context.startForegroundService(
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_VEHICLE)
            )
        } catch (_: Exception) {
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Repo.settings.value.autoEnabled && Repo.db.activeTrip() == null) return
        try {
            TrackingService.sync(context)
        } catch (_: Exception) {
            // Without "allow all the time" location, Android blocks this; the app restarts it when opened.
        }
    }
}
