package com.maciasdxv.igglass

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * Fija el margen inferior de unas pocas vistas de Instagram.
 *
 * Instagram vuelve a poner sus márgenes cada vez que se cambia de pestaña.
 * Corregirlos después del layout hace que la barra brinque un frame y corta la
 * animación del cambio de página. En vez de eso, [HookEntry] engancha
 * View.setLayoutParams y pasa por aquí: si la vista está registrada, el margen
 * se reescribe antes de aplicarse y el tamaño nunca llega a cambiar. Para los
 * cambios que no pasan por ahí está [beforeMeasureChild].
 */
internal object LayoutGuard {

    class Rule(
        /** El margen inferior que debe tener la vista ahora mismo. */
        val desired: () -> Int,
        /** Recibe el margen que Instagram intentaba poner (antes de reescribirlo). */
        val onIncoming: (Int) -> Unit = {},
    )

    private val rules = WeakHashMap<View, Rule>()

    fun register(view: View, rule: Rule) {
        rules[view] = rule
    }

    fun unregister(view: View) {
        rules.remove(view)
    }

    fun beforeSetLayoutParams(view: View, lp: ViewGroup.LayoutParams?) {
        if (rules.isEmpty()) return
        val rule = rules[view] ?: return
        val margins = lp as? ViewGroup.MarginLayoutParams ?: return
        rule.onIncoming(margins.bottomMargin)
        val want = rule.desired()
        if (margins.bottomMargin != want) {
            GlassLog.i("intercepto: ${nameOf(view)} mar.bottom ${margins.bottomMargin} -> $want")
            margins.bottomMargin = want
        }
    }

    /**
     * Instagram también cambia algunos márgenes escribiendo el campo directamente
     * y pidiendo layout, sin pasar por setLayoutParams. Todo contenedor lee los
     * márgenes de sus hijas en measureChildWithMargins: corregirlos ahí, antes de
     * medir, evita que la vista llegue a cambiar de tamaño.
     */
    fun beforeMeasureChild(child: View) {
        if (rules.isEmpty()) return
        val rule = rules[child] ?: return
        val margins = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val want = rule.desired()
        if (margins.bottomMargin != want) {
            rule.onIncoming(margins.bottomMargin)
            GlassLog.i("fijo al medir: ${nameOf(child)} mar.bottom ${margins.bottomMargin} -> ${rule.desired()}")
            margins.bottomMargin = rule.desired()
        }
    }

    fun nameOf(view: View): String = try {
        view.resources.getResourceEntryName(view.id)
    } catch (t: Throwable) {
        view.javaClass.simpleName
    }
}
