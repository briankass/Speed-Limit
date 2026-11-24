package com.speedalert.overlay

/** Makes "N. Main Street" and "North Main St" compare as the same road. */
object RoadNames {
    private val WORDS = mapOf(
        "street" to "st", "avenue" to "ave", "av" to "ave", "road" to "rd", "drive" to "dr",
        "boulevard" to "blvd", "lane" to "ln", "court" to "ct", "place" to "pl",
        "highway" to "hwy", "parkway" to "pkwy", "circle" to "cir", "terrace" to "ter",
        "trail" to "trl", "way" to "wy", "route" to "rt", "rte" to "rt", "expressway" to "expy",
        "freeway" to "fwy", "turnpike" to "tpke", "pike" to "pk", "square" to "sq",
        "north" to "n", "south" to "s", "east" to "e", "west" to "w",
        "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw",
        "saint" to "st", "mount" to "mt",
    )

    fun normalize(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val words = name.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .map { WORDS[it] ?: it }
        return words.joinToString(" ").ifBlank { null }
    }

    fun same(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        return na == normalize(b)
    }
}
