package com.speedalert.overlay

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Asks HERE or TomTom for the speed limit while you drive.
 *
 * Quota: asks again only after ~200 m, a turn, or 30 seconds.
 *
 * Sticky road: if an answer names a *different* road than the one you're on, and you
 * haven't actually turned, it's usually a cross street picked up near an intersection.
 * That answer is ignored unless the next lookup agrees.
 */
class OnlineLimitSource(private val source: Prefs.Source, private val key: String) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var busy = false
    private var forceNext = false
    private var lastQueryAt = 0L
    private var lastQueryLocation: Location? = null
    private var lastQueryBearing: Float? = null
    private var lastSuccessAt = 0L
    private var failures = 0

    // The road we've accepted, and a different road we've seen once but not confirmed
    private var acceptedLimit: Int? = null
    private var acceptedRoad: String? = null
    private var acceptedBearing: Float? = null
    private var pendingRoad: String? = null

    /** Fixes from the last few seconds, used as the starting point for HERE routes. */
    private val history = ArrayDeque<Location>()

    /** Latest accepted limit in mph, or null when unknown / too old. */
    val limit: Int?
        get() = if (fresh()) acceptedLimit else null

    /** Name of the road we think you're on (null if unknown / too old). */
    val road: String?
        get() = if (fresh()) acceptedRoad else null

    var lastError: String? = null
        private set

    val label: String = if (source == Prefs.Source.HERE) "HERE" else "TOMTOM"

    private fun fresh() = SystemClock.elapsedRealtime() - lastSuccessAt < MAX_AGE_MS

    fun onLocation(loc: Location, bearing: Float?, mph: Double?) {
        history.addLast(loc)
        while (history.size > 15) history.removeFirst()
        if (busy) return

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastQueryAt
        val minWait = if (failures == 0) MIN_INTERVAL_MS else minOf(60_000L, 5_000L * failures)
        if (sinceLast < minWait) return

        val prev = lastQueryLocation
        val moving = (mph ?: 0.0) >= 3.0
        // Parked: only ask once (so the road name is known), then wait until we move.
        if (!moving && prev != null) return

        val moved = prev?.distanceTo(loc) ?: Float.MAX_VALUE
        val turned = bearing != null && lastQueryBearing != null &&
            SpeedLimitProvider.angleDiff(bearing.toDouble(), lastQueryBearing!!.toDouble()) > 35.0
        if (!forceNext && moved < REQUERY_DISTANCE_M && !turned && sinceLast < MAX_INTERVAL_MS) return

        val from = if (source == Prefs.Source.HERE) {
            // A point at least ~25 m behind us so HERE knows which way we're going
            history.firstOrNull { it.distanceTo(loc) in 25f..250f } ?: return
        } else null

        busy = true
        forceNext = false
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
                    failures = 0
                    lastError = null
                    consider(it, bearing)
                }.onFailure {
                    failures++
                    lastError = it.message
                }
            }
        }
    }

    private fun consider(result: OnlineLimits.Result, bearing: Float?) {
        val haveRoad = acceptedRoad != null && fresh()
        val sameRoad = RoadNames.same(result.road, acceptedRoad)
        val reallyTurned = bearing != null && acceptedBearing != null &&
            SpeedLimitProvider.angleDiff(bearing.toDouble(), acceptedBearing!!.toDouble()) > 45.0
        val confirmed = result.road != null && RoadNames.same(result.road, pendingRoad)

        if (!haveRoad || sameRoad || reallyTurned || confirmed) {
            acceptedLimit = result.limit
            acceptedRoad = result.road
            if (bearing != null) acceptedBearing = bearing
            pendingRoad = null
            lastSuccessAt = SystemClock.elapsedRealtime()
        } else {
            // Probably a cross street: keep the current road, but check again soon
            pendingRoad = result.road ?: UNNAMED
            forceNext = true
            lastSuccessAt = SystemClock.elapsedRealtime() // current road is still valid
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
        private const val UNNAMED = "\u0000unnamed"
    }
}
