package com.maciasdxv.igglass

/**
 * Interruptores que se cambian desde el PC sin reinstalar ni reiniciar:
 *
 *   adb shell setprop debug.igglass.off 1    # desactiva el módulo
 *   adb shell setprop debug.igglass.off 0    # lo reactiva
 *   adb shell setprop debug.igglass.dump 1   # vuelca el árbol de vistas (diagnóstico)
 *
 * Las propiedades `debug.*` las puede escribir el usuario shell sin root y las
 * puede leer cualquier app. Se leen al reanudar cada Activity, así que basta
 * con salir y volver a Instagram para que el cambio aplique.
 */
internal object Config {
    private const val PROP_OFF = "debug.igglass.off"
    private const val PROP_DUMP = "debug.igglass.dump"

    private val getProp by lazy {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    }

    val isOff: Boolean
        get() = flag(PROP_OFF)

    val dumpEnabled: Boolean
        get() = flag(PROP_DUMP)

    private fun flag(prop: String): Boolean = try {
        getProp.invoke(null, prop, "0") == "1"
    } catch (t: Throwable) {
        // Si no se puede leer la propiedad, se toma el valor por defecto (apagado).
        false
    }
}
