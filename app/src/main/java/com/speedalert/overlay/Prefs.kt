package com.speedalert.overlay

import android.content.Context

/** All user settings, stored on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun getB(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** mph over the limit before the speeding alert fires: 0, 5, 10 or 15. */
    var tolerance: Int
        get() = sp.getInt("tolerance", 10)
        set(v) = sp.edit().putInt("tolerance", v).apply()

    var changeBeep: Boolean
        get() = getB("changeBeep", true)
        set(v) = putB("changeBeep", v)
    var changeVoice: Boolean
        get() = getB("changeVoice", true)
        set(v) = putB("changeVoice", v)
    var changeVibrate: Boolean
        get() = getB("changeVibrate", false)
        set(v) = putB("changeVibrate", v)

    var speedBeep: Boolean
        get() = getB("speedBeep", true)
        set(v) = putB("speedBeep", v)
    var speedVoice: Boolean
        get() = getB("speedVoice", false)
        set(v) = putB("speedVoice", v)
    var speedVibrate: Boolean
        get() = getB("speedVibrate", true)
        set(v) = putB("speedVibrate", v)

    var repeatWarning: Boolean
        get() = getB("repeatWarning", false)
        set(v) = putB("repeatWarning", v)

    var overlayX: Int
        get() = sp.getInt("overlayX", 24)
        set(v) = sp.edit().putInt("overlayX", v).apply()
    var overlayY: Int
        get() = sp.getInt("overlayY", 360)
        set(v) = sp.edit().putInt("overlayY", v).apply()
}
