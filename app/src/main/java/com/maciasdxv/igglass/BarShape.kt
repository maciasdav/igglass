package com.maciasdxv.igglass

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup

/**
 * Geometría de la barra según su orientación.
 *
 * En teléfonos Instagram usa una barra horizontal abajo (`tab_bar`). En
 * tablets y pantallas anchas usa un riel vertical a un lado. Cápsula, píldora
 * y arrastre trabajan sobre un "eje" (el de las pestañas) y un "través"
 * (el perpendicular), y esta clase traduce eso a x/y.
 *
 * Las pestañas no tienen por qué ser hijas directas de la barra: sus
 * posiciones se miden siempre respecto a ella.
 */
internal class BarShape(
    private val bar: ViewGroup,
    /** Las pestañas, dentro de [bar], en orden. */
    private val tabs: List<View>,
    /** true: riel vertical (tablets). false: barra horizontal abajo. */
    val vertical: Boolean,
) {

    private val density = bar.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** Borde izquierdo de [view] en coordenadas de la barra (sin transformaciones). */
    fun leftOf(view: View): Int = offsetOf(view, horizontal = true)

    /** Borde superior de [view] en coordenadas de la barra (sin transformaciones). */
    fun topOf(view: View): Int = offsetOf(view, horizontal = false)

    private fun offsetOf(view: View, horizontal: Boolean): Int {
        var sum = 0
        var v: View = view
        while (v !== bar) {
            sum += if (horizontal) v.left - (v.parent as? View)?.scrollX.orZero() else v.top - (v.parent as? View)?.scrollY.orZero()
            v = v.parent as? View ?: return sum
        }
        return sum
    }

    private fun Int?.orZero() = this ?: 0

    /** Centro de la pestaña [i] sobre el eje de la barra. */
    fun center(i: Int): Float {
        val tab = tabs[i]
        return if (vertical) topOf(tab) + tab.height / 2f else leftOf(tab) + tab.width / 2f
    }

    /** Centro de [tab] en coordenadas de la barra. */
    fun centerOf(tab: View, out: FloatArray) {
        out[0] = leftOf(tab) + tab.width / 2f
        out[1] = topOf(tab) + tab.height / 2f
    }

    /** Tamaño de la pestaña [i] sobre el eje de la barra. */
    fun size(i: Int): Float = if (vertical) tabs[i].height.toFloat() else tabs[i].width.toFloat()

    /** Distancia media entre centros de pestañas vecinas, en px. */
    fun step(): Float {
        if (tabs.size < 2) return 0f
        return kotlin.math.abs(center(tabs.lastIndex) - center(0)) / tabs.lastIndex
    }

    /**
     * Posición en pasos de pestaña para una coordenada sobre el eje. Interpola
     * entre los centros reales, así que sirve aunque las pestañas no estén
     * repartidas por igual (en el riel el perfil va separado) o vayan en RTL.
     * Fuera de los extremos sigue la recta del tramo más cercano.
     */
    fun positionAt(coord: Float): Float {
        val n = tabs.size
        if (n < 2) return 0f
        for (i in 0 until n - 1) {
            val a = center(i)
            val b = center(i + 1)
            if (a == b) continue
            val f = (coord - a) / (b - a)
            if (f in 0f..1f) return i + f
        }
        val a0 = center(0)
        val b0 = center(1)
        val before = if (a0 != b0) (coord - a0) / (b0 - a0) else 0f
        if (before < 0f) return before
        val a1 = center(n - 2)
        val b1 = center(n - 1)
        return if (a1 != b1) (n - 2) + (coord - a1) / (b1 - a1) else (n - 1).toFloat()
    }

    /**
     * Separación entre la cápsula y los lados de la barra horizontal. En
     * pantallas anchas la cápsula no pasa de [GlassParams.CAPSULE_MAX_WIDTH_DP]
     * y queda centrada, como la barra de iPadOS.
     */
    fun horizontalMargin(): Float {
        val minMargin = dp(GlassParams.CAPSULE_MARGIN_H_DP)
        val maxWidth = dp(GlassParams.CAPSULE_MAX_WIDTH_DP)
        if (maxWidth <= 0f) return minMargin
        return maxOf(minMargin, (bar.width - maxWidth) / 2f)
    }

    /**
     * La cápsula en coordenadas de la barra.
     *  - Horizontal: todo el alto, separada de los lados.
     *  - Vertical: envuelve solo las pestañas (el riel ocupa todo el alto de la
     *    pantalla) y se separa de los lados del riel.
     */
    fun capsule(out: RectF): Boolean {
        if (!vertical) {
            val margin = horizontalMargin()
            out.set(margin, 0f, bar.width - margin, bar.height.toFloat())
        } else {
            val visible = tabs.filter { it.visibility == View.VISIBLE && it.height > 0 }
            if (visible.isEmpty()) {
                out.setEmpty()
                return false
            }
            val margin = dp(GlassParams.RAIL_MARGIN_DP)
            val pad = dp(GlassParams.RAIL_PADDING_DP)
            val top = (visible.minOf { topOf(it) }.toFloat() - pad).coerceAtLeast(0f)
            val bottom = (visible.maxOf { topOf(it) + it.height }.toFloat() + pad).coerceAtMost(bar.height.toFloat())
            out.set(margin, top, bar.width - margin, bottom)
        }
        return out.width() > 0f && out.height() > 0f
    }
}
