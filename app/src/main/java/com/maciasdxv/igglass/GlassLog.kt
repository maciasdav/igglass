package com.maciasdxv.igglass

import android.util.Log

/** Todo el módulo escribe con esta etiqueta: `adb logcat -s IGGlass`. */
internal object GlassLog {
    const val TAG = "IGGlass"

    fun i(msg: String) = Log.i(TAG, msg)

    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
}

/**
 * Ejecuta [block] y se traga cualquier excepción. Cada hook pasa por aquí:
 * un fallo del módulo se queda en logcat y nunca llega a Instagram.
 */
internal inline fun safe(what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        GlassLog.w("fallo en $what", t)
    }
}
