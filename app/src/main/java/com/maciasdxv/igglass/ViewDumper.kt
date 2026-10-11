package com.maciasdxv.igglass

import android.app.Activity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vuelca el árbol de vistas de una Activity para localizar la barra de
 * pestañas y saber con qué tipo de vistas está hecha la interfaz.
 *
 * - Árbol completo: a un archivo en el almacenamiento externo de Instagram
 *   (logcat corta o descarta líneas cuando el árbol tiene miles de vistas).
 * - Resumen y candidatos a barra de pestañas: a logcat.
 */
internal object ViewDumper {

    /** Una vista anclada abajo, a ancho completo y de esta altura parece una barra de pestañas. */
    private const val BAR_MIN_HEIGHT_DP = 40
    private const val BAR_MAX_HEIGHT_DP = 120
    /** Margen sobre el borde inferior para la barra de navegación del sistema. */
    private const val BAR_BOTTOM_SLACK_DP = 80

    fun dump(activity: Activity, label: String? = null) {
        val root = activity.window?.decorView ?: return
        val density = root.resources.displayMetrics.density
        val rootLoc = IntArray(2).also(root::getLocationOnScreen)
        val rootBottom = rootLoc[1] + root.height

        val lines = ArrayList<String>(2048)
        val candidates = ArrayList<String>()
        val stats = Stats()

        fun visit(view: View, depth: Int) {
            val loc = IntArray(2).also(view::getLocationOnScreen)
            val id = idName(view)
            val tags = tagsOf(view, stats)
            val line = buildString {
                append("  ".repeat(depth))
                append(view.javaClass.name)
                if (id != null) append(" id=").append(id)
                append(" [").append(loc[0]).append(',').append(loc[1])
                append(' ').append(view.width).append('x').append(view.height).append(']')
                append(' ').append(visibilityOf(view))
                if (view.alpha < 1f) append(" a=").append("%.2f".format(view.alpha))
                view.background?.let { append(" bg=").append(it.javaClass.simpleName) }
                if (view is ViewGroup) append(" hijos=").append(view.childCount)
                if (view.paddingLeft or view.paddingTop or view.paddingRight or view.paddingBottom != 0) {
                    append(" pad=").append(view.paddingLeft).append(',').append(view.paddingTop)
                        .append(',').append(view.paddingRight).append(',').append(view.paddingBottom)
                }
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin or lp.topMargin or lp.rightMargin or lp.bottomMargin != 0) {
                        append(" mar=").append(lp.leftMargin).append(',').append(lp.topMargin)
                            .append(',').append(lp.rightMargin).append(',').append(lp.bottomMargin)
                    }
                }
                view.layoutParams?.let { lp -> append(" lp=").append(sizeSpec(lp.width)).append('x').append(sizeSpec(lp.height)) }
                if (view.fitsSystemWindows) append(" fitsSW")
                if (view is ViewGroup && !view.clipChildren) append(" noClipChildren")
                if (tags.isNotEmpty()) append(' ').append(tags)
            }
            lines += line
            stats.total++

            val heightDp = view.height / density
            val fromBottomDp = (rootBottom - (loc[1] + view.height)) / density
            val isBarLike = view.isShown &&
                view is ViewGroup &&
                view.width >= root.width * 0.9f &&
                heightDp in BAR_MIN_HEIGHT_DP.toFloat()..BAR_MAX_HEIGHT_DP.toFloat() &&
                fromBottomDp in 0f..BAR_BOTTOM_SLACK_DP.toFloat()
            if (isBarLike) {
                candidates += "CANDIDATO_BARRA ${view.javaClass.name} id=$id " +
                    "alto=${heightDp.toInt()}dp hijos=${(view as ViewGroup).childCount} " +
                    "a ${fromBottomDp.toInt()}dp del borde"
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.let { visit(it, depth + 1) }
                }
            }
        }

        visit(root, 0)

        val name = activity.javaClass.simpleName + (label?.let { "_$it" } ?: "")
        val file = writeFile(activity, name, lines)
        GlassLog.i(
            "volcado $name: ${stats.total} vistas, surface=${stats.surface} " +
                "texture=${stats.texture} compose=${stats.compose} litho=${stats.litho} " +
                "rn=${stats.reactNative} -> ${file ?: "(no se pudo escribir archivo)"}",
        )
        if (candidates.isEmpty()) GlassLog.i("sin candidatos a barra de pestañas")
        candidates.forEach(GlassLog::i)
    }

    private class Stats {
        var total = 0
        var surface = 0
        var texture = 0
        var compose = 0
        var litho = 0
        var reactNative = 0
    }

    /** Nombre del recurso de ID, que se mantiene entre versiones aunque el número cambie. */
    private fun idName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || id ushr 24 == 0) return null
        return try {
            val res = view.resources
            "${res.getResourcePackageName(id)}:${res.getResourceEntryName(id)}"
        } catch (t: Throwable) {
            "0x" + Integer.toHexString(id)
        }
    }

    private fun tagsOf(view: View, stats: Stats): String {
        val cls = view.javaClass.name
        val tags = ArrayList<String>(2)
        when {
            // SurfaceView no se puede capturar en un RenderNode: saldría negro en el blur.
            view is SurfaceView -> { tags += "[SURFACE]"; stats.surface++ }
            view is TextureView -> { tags += "[TEXTURE]"; stats.texture++ }
        }
        when {
            cls.contains("ComposeView") -> { tags += "[COMPOSE]"; stats.compose++ }
            cls.contains("Litho", ignoreCase = true) -> { tags += "[LITHO]"; stats.litho++ }
            cls.contains("ReactRootView") || cls.startsWith("com.facebook.react") -> {
                tags += "[RN]"; stats.reactNative++
            }
        }
        return tags.joinToString(" ")
    }

    private fun sizeSpec(v: Int) = when (v) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap"
        else -> v.toString()
    }

    private fun visibilityOf(view: View) = when (view.visibility) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        else -> "G"
    }

    /**
     * Escribe en /sdcard/Android/data/com.instagram.android/files/igglass/,
     * que se puede bajar con `adb pull` sin root.
     */
    private fun writeFile(activity: Activity, name: String, lines: List<String>): String? = try {
        val dir = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "igglass")
        dir.mkdirs()
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val file = File(dir, "dump_${name}_$stamp.txt")
        file.writeText(lines.joinToString("\n"))
        file.absolutePath
    } catch (t: Throwable) {
        GlassLog.w("no se pudo escribir el volcado", t)
        null
    }
}
