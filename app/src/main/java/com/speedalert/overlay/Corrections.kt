package com.speedalert.overlay

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own speed limit fixes, e.g. "Oak St near home is 25, not 35".
 * A fix applies when you're on a road with that name within [radiusM] of where it was saved.
 * Stored only on the phone.
 */
data class Correction(
    val road: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val mph: Int,
) {
    val normRoad: String? = RoadNames.normalize(road)

    fun appliesTo(roadName: String?, lat: Double, lon: Double): Boolean {
        if (normRoad == null || normRoad != RoadNames.normalize(roadName)) return false
        val d = FloatArray(1)
        Location.distanceBetween(this.lat, this.lon, lat, lon, d)
        return d[0] <= radiusM
    }
}

class Corrections(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("corrections", Context.MODE_PRIVATE)

    fun load(): List<Correction> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Correction(
                    o.getString("road"), o.getDouble("lat"), o.getDouble("lon"),
                    o.getInt("radius"), o.getInt("mph"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<Correction>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("road", it.road).put("lat", it.lat).put("lon", it.lon)
                    .put("radius", it.radiusM).put("mph", it.mph)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(c: Correction) {
        // Replace an existing fix for the same road at (roughly) the same spot
        val kept = load().filterNot { it.normRoad == c.normRoad && it.appliesTo(c.road, c.lat, c.lon) && it.radiusM == c.radiusM }
        save(kept + c)
    }

    fun remove(c: Correction) = save(load().filterNot { it == c })

    /** The closest-fitting fix (smallest radius wins), or null. */
    fun find(list: List<Correction>, roadName: String?, lat: Double, lon: Double): Correction? =
        list.filter { it.appliesTo(roadName, lat, lon) }.minByOrNull { it.radiusM }

    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val KEY = "list"
    }
}
