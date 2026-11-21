package com.speedalert.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Runs while you drive: reads GPS, looks up the speed limit, updates the bubble
 * and plays alerts. Shows an ongoing notification with a Stop button.
 */
class OverlayService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "com.speedalert.overlay.STOP"
        private const val CHANNEL_ID = "speed_alert"
        private const val NOTIFICATION_ID = 1
        private const val MS_TO_MPH = 2.236936

        /** Seconds to wait after a limit change before warning (time to slow down). */
        private const val GRACE_AFTER_CHANGE_MS = 6_000L
        private const val REPEAT_WARNING_MS = 30_000L
        private const val GPS_STALE_MS = 5_000L

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var prefs: Prefs
    private lateinit var alerts: AlertManager
    private lateinit var provider: SpeedLimitProvider
    private lateinit var overlay: OverlayView
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    // Road / limit state
    private var currentWayId: Long? = null
    private var candidateWayId: Long? = null
    private var candidateCount = 0
    private var currentLimit: Int? = null
    private var lastAnnouncedLimit: Int? = null
    private var lastLimitChangeAt = 0L

    // Speed state
    private var speeding = false
    private var overCount = 0
    private var lastSpeedingAlertAt = 0L
    private var lastFixAt = 0L
    private var lastLocation: Location? = null

    private val staleCheck = object : Runnable {
        override fun run() {
            if (started && SystemClock.elapsedRealtime() - lastFixAt > GPS_STALE_MS) {
                overlay.setSpeed(null)
                overlay.setLevel(OverlayView.Level.UNKNOWN)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (!started) begin()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Speed Alert", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle("Speed Alert is running")
            .setContentText("Warning at +${Prefs(this).tolerance} mph over the limit")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun begin() {
        started = true
        isRunning = true
        prefs = Prefs(this)
        alerts = AlertManager(this)
        provider = SpeedLimitProvider()
        overlay = OverlayView(this, prefs)
        overlay.show()

        locationManager = getSystemService(LocationManager::class.java)
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
            return
        } catch (e: IllegalArgumentException) {
            // No GPS hardware
            stopSelf()
            return
        }
        handler.postDelayed(staleCheck, 1_000)
    }

    override fun onDestroy() {
        isRunning = false
        if (started) {
            started = false
            handler.removeCallbacksAndMessages(null)
            locationManager.removeUpdates(this)
            overlay.hide()
            alerts.shutdown()
            provider.shutdown()
        }
        super.onDestroy()
    }

    // ---------------- GPS ----------------

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        lastFixAt = now

        val mph: Double? = when {
            location.hasSpeed() -> location.speed * MS_TO_MPH
            lastLocation != null -> {
                val prev = lastLocation!!
                val seconds = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
                if (seconds > 0) prev.distanceTo(location) / seconds * MS_TO_MPH else null
            }
            else -> null
        }
        lastLocation = location

        // GPS heading is only reliable when moving
        val bearing = if (location.hasBearing() && (mph ?: 0.0) > 5.0) location.bearing else null

        provider.onLocation(location.latitude, location.longitude)
        if (location.accuracy <= 50f) {
            updateRoad(provider.match(location.latitude, location.longitude, bearing), now)
        }
        overlay.setLimit(currentLimit, provider.isOffline && currentLimit == null)
        overlay.setSpeed(mph)
        updateSpeeding(mph, now)
    }

    /** Only switch roads after two fixes in a row agree (prevents flicker at intersections). */
    private fun updateRoad(match: SpeedLimitProvider.Match?, now: Long) {
        val id = match?.way?.id
        if (id == currentWayId) {
            candidateWayId = null
            candidateCount = 0
            setLimit(match?.limitMph, now)
            return
        }
        if (id == candidateWayId) candidateCount++ else {
            candidateWayId = id
            candidateCount = 1
        }
        val needed = if (id == null) 3 else 2
        if (candidateCount >= needed || (currentWayId == null && id != null)) {
            currentWayId = id
            candidateWayId = null
            candidateCount = 0
            setLimit(match?.limitMph, now)
        }
    }

    private fun setLimit(limit: Int?, now: Long) {
        if (limit == currentLimit) return
        currentLimit = limit
        if (limit != null && limit != lastAnnouncedLimit) {
            val previous = lastAnnouncedLimit
            lastAnnouncedLimit = limit
            lastLimitChangeAt = now
            overlay.flashNewLimit(previous, limit)
            alerts.limitChanged(limit, previous, prefs.changeBeep, prefs.changeVoice, prefs.changeVibrate)
        }
    }

    private fun updateSpeeding(mph: Double?, now: Long) {
        val limit = currentLimit
        if (limit == null || mph == null) {
            overCount = 0
            speeding = false
            overlay.showSpeedingMessage(null)
            overlay.setLevel(if (mph == null) OverlayView.Level.UNKNOWN else OverlayView.Level.OK)
            return
        }

        val threshold = limit + prefs.tolerance
        if (mph > threshold + 0.5) overCount++ else overCount = 0

        if (!speeding) {
            // Must be over for 2 seconds, and not right after a limit change
            if (overCount >= 2 && now - lastLimitChangeAt > GRACE_AFTER_CHANGE_MS) {
                speeding = true
                fireSpeedingAlert(limit, mph, now)
            }
        } else {
            if (mph < threshold - 2.0) {
                speeding = false // 2 mph of slack so it doesn't beep on and off
            } else if (prefs.repeatWarning && now - lastSpeedingAlertAt > REPEAT_WARNING_MS) {
                fireSpeedingAlert(limit, mph, now)
            }
        }

        val overBy = (mph - limit).roundToInt()
        overlay.showSpeedingMessage(if (speeding) overBy else null)
        overlay.setLevel(
            when {
                speeding || mph > threshold + 0.5 -> OverlayView.Level.SPEEDING
                mph > limit + 0.5 -> OverlayView.Level.OVER
                else -> OverlayView.Level.OK
            }
        )
    }

    private fun fireSpeedingAlert(limit: Int, mph: Double, now: Long) {
        lastSpeedingAlertAt = now
        alerts.speeding(
            limit, (mph - limit).roundToInt(),
            prefs.speedBeep, prefs.speedVoice, prefs.speedVibrate
        )
    }

    // Required on Android 9 and older, where these aren't optional.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
