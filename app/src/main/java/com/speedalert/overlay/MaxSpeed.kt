package com.speedalert.overlay

import kotlin.math.roundToInt

/** Turns an OpenStreetMap "maxspeed" tag into mph (or null if unknown). */
object MaxSpeed {
    private val NUMBER = Regex("""^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh|kph|knots)?$""")

    fun toMph(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        // "35 mph;25 mph" (multiple values) -> take the first one
        val s = raw.split(';').first().trim().lowercase()
        val m = NUMBER.matchEntire(s) ?: return null // "none", "signals", "US:urban", ...
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mph = when (m.groupValues[2]) {
            "mph" -> value
            "knots" -> value * 1.15078
            else -> value * 0.621371 // plain numbers are km/h in OSM
        }
        val r = mph.roundToInt()
        return if (r in 1..120) r else null
    }
}
