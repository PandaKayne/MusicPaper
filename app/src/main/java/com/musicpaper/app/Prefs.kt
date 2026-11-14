package com.musicpaper.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("musicpaper", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** Paquetes de apps de música elegidas. Vacío = todas. */
    var apps: Set<String>
        get() = HashSet(sp.getStringSet("apps", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("apps", HashSet(v)).apply() }

    var target: Int
        get() = sp.getInt("target", TARGET_BOTH)
        set(v) { sp.edit().putInt("target", v).apply() }

    var effect: String
        get() = sp.getString("effect", EFFECT_BLUR) ?: EFFECT_BLUR
        set(v) { sp.edit().putString("effect", v).apply() }

    var blur: Int
        get() = sp.getInt("blur", 80)
        set(v) { sp.edit().putInt("blur", v).apply() }

    var darken: Int
        get() = sp.getInt("darken", 0)
        set(v) { sp.edit().putInt("darken", v).apply() }

    var restoreDelay: Int
        get() = sp.getInt("restoreDelay", 5)
        set(v) { sp.edit().putInt("restoreDelay", v).apply() }

    companion object {
        const val TARGET_BOTH = 0
        const val TARGET_LOCK = 1
        const val TARGET_HOME = 2
        const val EFFECT_BLUR = "blur"
        const val EFFECT_COVER = "cover"
    }
}
