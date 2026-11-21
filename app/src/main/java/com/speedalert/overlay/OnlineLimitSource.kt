package com.speedalert.overlay

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Asks HERE or TomTom for the speed limit while you drive, without using up your
 * free quota: it asks again only after you've gone ~200 m, turned onto another road,
 * or 30 seconds have passed.
 */
class OnlineLimitSource(private val source: Prefs.Source, private val key: String) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var busy = false
    private var lastQueryAt = 0L
    private var lastQueryLocation: Location? = null
    private var lastQueryBearing: Float? = null
    private var lastSuccessAt = 0L
    private var failures = 0

    /** Fixes from the last few seconds, used as the starting point for HERE routes. */
    private val history = ArrayDeque<Location>()

    private var result: Int? = null

    /** Latest limit in mph, or null when unknown / too old. */
    val limit: Int?
        get() = if (SystemClock.elapsedRealtime() - lastSuccessAt < MAX_AGE_MS) result else null

    var lastError: String? = null
        private set

    val label: String = if (source == Prefs.Source.HERE) "HERE" else "TOMTOM"

    fun onLocation(loc: Location, bearing: Float?, mph: Double?) {
        history.addLast(loc)
        while (history.size > 15) history.removeFirst()

        if (busy || (mph ?: 0.0) < 3.0) return // parked or crawling: no need to ask
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastQueryAt
        val minWait = if (failures == 0) MIN_INTERVAL_MS else minOf(60_000L, 5_000L * failures)
        if (sinceLast < minWait) return

        val prev = lastQueryLocation
        val moved = prev?.distanceTo(loc) ?: Float.MAX_VALUE
        val turned = bearing != null && lastQueryBearing != null &&
            SpeedLimitProvider.angleDiff(bearing.toDouble(), lastQueryBearing!!.toDouble()) > 35.0
        if (moved < REQUERY_DISTANCE_M && !turned && sinceLast < MAX_INTERVAL_MS) return

        val from = if (source == Prefs.Source.HERE) {
            // A point at least ~25 m behind us so HERE knows which way we're going
            history.firstOrNull { it.distanceTo(loc) in 25f..250f } ?: return
        } else null

        busy = true
        lastQueryAt = now
        lastQueryLocation = loc
        lastQueryBearing = bearing
        executor.execute {
            val r = runCatching {
                if (source == Prefs.Source.HERE) {
                    OnlineLimits.here(key, from!!.latitude, from.longitude, loc.latitude, loc.longitude)
                } else {
                    OnlineLimits.tomtom(key, loc.latitude, loc.longitude, bearing)
                }
            }
            main.post {
                busy = false
                r.onSuccess {
                    result = it
                    lastSuccessAt = SystemClock.elapsedRealtime()
                    failures = 0
                    lastError = null
                }.onFailure {
                    failures++
                    lastError = it.message
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 4_000L
        private const val MAX_INTERVAL_MS = 30_000L
        private const val REQUERY_DISTANCE_M = 200f
        private const val MAX_AGE_MS = 45_000L
    }
}
