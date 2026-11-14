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
        get() {
            val v = sp.getString("effect", EFFECT_BLUR) ?: EFFECT_BLUR
            return if (v == EFFECT_COMBO) EFFECT_BLUR else v   // "combo" era la versión anterior
        }
        set(v) { sp.edit().putString("effect", v).apply() }

    /** Dibuja la carátula nítida encima del fondo desenfocado. */
    var showArt: Boolean
        get() = sp.getBoolean("showArt", true)
        set(v) { sp.edit().putBoolean("showArt", v).apply() }

    /** Tamaño de la carátula, % del ancho de pantalla (30..100). */
    var artSize: Int
        get() = sp.getInt("artSize", 88)
        set(v) { sp.edit().putInt("artSize", v).apply() }

    /** Posición del centro de la carátula, % del ancho y del alto (0..100). */
    var artX: Int
        get() = sp.getInt("artX", 50)
        set(v) { sp.edit().putInt("artX", v).apply() }

    var artY: Int
        get() = sp.getInt("artY", 52)
        set(v) { sp.edit().putInt("artY", v).apply() }

    /** Muestra la sección opcional de ayuda con ajustes del teléfono. */
    var showHelp: Boolean
        get() = sp.getBoolean("showHelp", false)
        set(v) { sp.edit().putBoolean("showHelp", v).apply() }

    /** Datos de diagnóstico que escribe el servicio y lee la pantalla "Estado". */
    fun getDiag(key: String): String = sp.getString("diag_$key", "") ?: ""

    fun putDiag(key: String, value: String) {
        sp.edit().putString("diag_$key", value).apply()
    }

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
        const val EFFECT_COMBO = "combo"
        const val EFFECT_CD = "cd"
        const val EFFECT_SQUARE = "square"
        const val EFFECT_GLASS = "glass"
        const val EFFECT_CD_SPIN = "cdspin"
    }
}
