package com.speedalert.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Downloads nearby roads (with their speed limits) from OpenStreetMap's Overpass API,
 * keeps them in memory, and matches each GPS position to the most likely road.
 * One download covers ~800 m around you, so it only re-downloads every ~400 m.
 */
class SpeedLimitProvider {

    class Pt(val lat: Double, val lon: Double)

    class Way(
        val id: Long,
        val name: String?,
        val limit: Int?,
        val forwardLimit: Int?,
        val backwardLimit: Int?,
        /** 1 = one-way along the drawing direction, -1 = against it, 0 = two-way */
        val oneway: Int,
        val pts: List<Pt>,
    )

    class Match(val way: Way, val limitMph: Int?)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var ways: List<Way> = emptyList()
    private var fetchCenter: Pt? = null
    private var fetching = false
    private var lastAttemptAt = 0L
    private var failures = 0

    /** True when the last download failed (e.g. no signal). */
    val isOffline: Boolean get() = failures > 0

    /** Call on every GPS fix; downloads more roads when needed. */
    fun onLocation(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        val c = fetchCenter
        val needed = c == null || distanceM(c.lat, c.lon, lat, lon) > REFETCH_DISTANCE_M
        val wait = if (failures == 0) MIN_INTERVAL_MS else min(60_000L, 5_000L * failures)
        if (needed && !fetching && now - lastAttemptAt > wait) {
            fetch(lat, lon)
        }
    }

    private fun fetch(lat: Double, lon: Double) {
        fetching = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        executor.execute {
            val result = runCatching { download(lat, lon) }
            main.post {
                fetching = false
                result.onSuccess {
                    ways = it
                    fetchCenter = Pt(lat, lon)
                    failures = 0
                }.onFailure {
                    failures++
                }
            }
        }
    }

    /** Find the road you are most likely on. [bearing] is your heading in degrees, if known. */
    fun match(lat: Double, lon: Double, bearing: Float?): Match? {
        val cosLat = cos(Math.toRadians(lat))
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        var bestForward = true

        for (w in ways) {
            val pts = w.pts
            for (i in 0 until pts.size - 1) {
                // Local flat coordinates in meters, with us at (0,0)
                val ax = (pts[i].lon - lon) * cosLat * M_PER_DEG
                val ay = (pts[i].lat - lat) * M_PER_DEG
                val bx = (pts[i + 1].lon - lon) * cosLat * M_PER_DEG
                val by = (pts[i + 1].lat - lat) * M_PER_DEG
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx
                val py = ay + t * dy
                val dist = sqrt(px * px + py * py)
                if (dist > MAX_MATCH_DISTANCE_M) continue

                var penalty = 0.0
                var forward = true
                if (bearing != null && len2 > 0.0) {
                    val segBearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                    val diffForward = angleDiff(bearing.toDouble(), segBearing)
                    forward = diffForward <= 90.0
                    val diff = when (w.oneway) {
                        1 -> diffForward
                        -1 -> 180.0 - diffForward
                        else -> min(diffForward, 180.0 - diffForward)
                    }
                    penalty = diff / 90.0 * HEADING_WEIGHT_M
                }
                val score = dist + penalty
                if (score < bestScore) {
                    bestScore = score
                    best = w
                    bestForward = forward
                }
            }
        }
        val w = best ?: return null
        val limit = if (bestForward) w.forwardLimit ?: w.limit else w.backwardLimit ?: w.limit
        return Match(w, limit)
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    // ---------- networking ----------

    private fun download(lat: Double, lon: Double): List<Way> {
        val query = String.format(Locale.US, QUERY, RADIUS_M, lat, lon)
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                return parse(post(endpoint, query))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No server available")
    }

    private fun post(endpoint: String, query: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "SpeedAlertOverlay/1.0 (Android)")
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Way> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Way>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val geom = e.optJSONArray("geometry") ?: continue
            val pts = ArrayList<Pt>(geom.length())
            for (j in 0 until geom.length()) {
                val g = geom.optJSONObject(j) ?: continue
                pts.add(Pt(g.getDouble("lat"), g.getDouble("lon")))
            }
            if (pts.size < 2) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun tag(k: String): String? = if (tags.has(k)) tags.optString(k) else null

            val highway = tag("highway") ?: ""
            val onewayTag = tag("oneway")
            val oneway = when {
                onewayTag == "-1" -> -1
                onewayTag == "yes" || onewayTag == "1" || onewayTag == "true" -> 1
                onewayTag == "no" -> 0
                highway == "motorway" || tag("junction") == "roundabout" -> 1
                else -> 0
            }
            out.add(
                Way(
                    id = e.getLong("id"),
                    name = tag("name") ?: tag("ref"),
                    limit = MaxSpeed.toMph(tag("maxspeed")),
                    forwardLimit = MaxSpeed.toMph(tag("maxspeed:forward")),
                    backwardLimit = MaxSpeed.toMph(tag("maxspeed:backward")),
                    oneway = oneway,
                    pts = pts,
                )
            )
        }
        return out
    }

    companion object {
        private const val RADIUS_M = 800
        private const val REFETCH_DISTANCE_M = 400.0
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_MATCH_DISTANCE_M = 40.0
        private const val HEADING_WEIGHT_M = 20.0
        private const val M_PER_DEG = 111_195.0

        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )

        private const val ROAD_TYPES =
            "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
                "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

        private const val QUERY =
            "[out:json][timeout:25];way(around:%d,%.6f,%.6f)[highway~\"^($ROAD_TYPES)\$\"];out tags geom;"

        fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * M_PER_DEG
            val y = (lat2 - lat1) * M_PER_DEG
            return sqrt(x * x + y * y)
        }
    }
}
