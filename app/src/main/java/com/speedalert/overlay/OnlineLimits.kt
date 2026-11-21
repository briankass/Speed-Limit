package com.speedalert.overlay

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** One-shot speed limit lookups from HERE and TomTom. Results are in mph (null = no data). */
object OnlineLimits {

    class ApiException(message: String) : IOException(message)

    /**
     * HERE Routing v8: route a tiny stretch from where you were a moment ago to where you
     * are now, and read the speed limit of the last piece of road.
     */
    fun here(key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int? {
        val url = String.format(
            Locale.US,
            "https://router.hereapi.com/v8/routes?transportMode=car" +
                "&origin=%.6f,%.6f&destination=%.6f,%.6f" +
                "&return=polyline&spans=speedLimit&apiKey=%s",
            fromLat, fromLon, toLat, toLon, enc(key)
        )
        val json = JSONObject(get(url, "HERE"))
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) return null
        val sections = routes.getJSONObject(0).optJSONArray("sections") ?: return null
        if (sections.length() == 0) return null
        val spans = sections.getJSONObject(sections.length() - 1).optJSONArray("spans") ?: return null
        if (spans.length() == 0) return null
        val last = spans.getJSONObject(spans.length() - 1)
        if (!last.has("speedLimit")) return null
        val metersPerSecond = last.optDouble("speedLimit", Double.NaN)
        if (metersPerSecond.isNaN() || metersPerSecond <= 0) return null
        return sane((metersPerSecond * 2.236936).roundToInt())
    }

    /** TomTom Reverse Geocoding with returnSpeedLimit=true. */
    fun tomtom(key: String, lat: Double, lon: Double, heading: Float?): Int? {
        val sb = StringBuilder(
            String.format(
                Locale.US,
                "https://api.tomtom.com/search/2/reverseGeocode/%.6f,%.6f.json" +
                    "?key=%s&returnSpeedLimit=true&radius=50",
                lat, lon, enc(key)
            )
        )
        if (heading != null) sb.append(String.format(Locale.US, "&heading=%.0f", heading))
        val json = JSONObject(get(sb.toString(), "TomTom"))
        val addresses = json.optJSONArray("addresses") ?: return null
        if (addresses.length() == 0) return null
        val address = addresses.getJSONObject(0).optJSONObject("address") ?: return null
        return parseTomTom(address.optString("speedLimit", ""))
    }

    private val TOMTOM_LIMIT = Regex("""^\s*(\d+(?:\.\d+)?)\s*(MPH|KPH|KMH|KM/H)\s*$""", RegexOption.IGNORE_CASE)

    /** "35.00MPH" -> 35, "50.00KPH" -> 31 */
    fun parseTomTom(raw: String): Int? {
        val m = TOMTOM_LIMIT.matchEntire(raw) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mph = if (m.groupValues[2].equals("MPH", ignoreCase = true)) value else value * 0.621371
        return sane(mph.roundToInt())
    }

    private fun sane(mph: Int): Int? = if (mph in 1..120) mph else null

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private fun get(url: String, name: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "SpeedAlertOverlay/1.0 (Android)")
            val code = conn.responseCode
            when (code) {
                200 -> return conn.inputStream.bufferedReader().use { it.readText() }
                400 -> throw ApiException("$name: bad request (400)")
                401, 403 -> throw ApiException("$name: API key not accepted ($code)")
                429 -> throw ApiException("$name: daily/rate limit reached (429)")
                else -> throw ApiException("$name: server error ($code)")
            }
        } finally {
            conn.disconnect()
        }
    }
}
