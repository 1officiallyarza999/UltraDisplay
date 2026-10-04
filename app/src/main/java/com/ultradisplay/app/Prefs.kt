package com.ultradisplay.app

import android.content.Context
import android.content.SharedPreferences

/** All persisted settings in one place. */
class Prefs private constructor(private val p: SharedPreferences) {
    companion object {
        @Volatile private var instance: Prefs? = null
        fun of(ctx: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(ctx.applicationContext.getSharedPreferences("ultra", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun sb(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    var onboarded: Boolean get() = b("onboarded", false); set(v) = sb("onboarded", v)
    var mode: String? get() = p.getString("mode", null); set(v) = p.edit().putString("mode", v).apply()
    var preset: Int get() = p.getInt("preset", 0); set(v) = p.edit().putInt("preset", v).apply()
    var tabletMode: Boolean get() = b("tablet", false); set(v) = sb("tablet", v)
    var audio: Int get() = p.getInt("audio", 0); set(v) = p.edit().putInt("audio", v).apply()
    var fill: Boolean get() = b("fill", false); set(v) = sb("fill", v)
    var lang: String get() = p.getString("lang", "auto") ?: "auto"; set(v) = p.edit().putString("lang", v).apply()

    var autoConnect: Boolean get() = b("autoConnect", true); set(v) = sb("autoConnect", v)
    var autoStream: Boolean get() = b("autoStream", true); set(v) = sb("autoStream", v)
    var screenOff: Boolean get() = b("screenOff", false); set(v) = sb("screenOff", v)
    var adaptive: Boolean get() = b("adaptive", true); set(v) = sb("adaptive", v)
    var smartProfile: Boolean get() = b("smart", true); set(v) = sb("smart", v)
    var matchTablet: Boolean get() = b("matchTablet", true); set(v) = sb("matchTablet", v)
    /** Shell command that restores the phone's own resolution; set while it is matched to the tablet. */
    var restoreCmd: String? get() = p.getString("restoreCmd", null); set(v) = p.edit().putString("restoreCmd", v).apply()
    var forwardInput: Boolean get() = b("forwardInput", true); set(v) = sb("forwardInput", v)
}
