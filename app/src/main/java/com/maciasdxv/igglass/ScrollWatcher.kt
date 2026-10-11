package com.maciasdxv.igglass

import android.view.View

/**
 * Reparte los desplazamientos verticales de las listas de Instagram.
 *
 * [HookEntry] engancha View.onScrollChanged, que RecyclerView llama en cada
 * desplazamiento con el delta en (t − oldt) y ScrollView con su scroll real.
 * Es una clase del sistema, así que no depende de nombres ofuscados.
 */
internal object ScrollWatcher {

    fun interface Listener {
        /** [view] se desplazó [dy] px (positivo = el contenido sube, el dedo va hacia arriba). */
        fun onScroll(view: View, dy: Int)
    }

    private val listeners = ArrayList<Listener>(2)

    fun add(listener: Listener) {
        listeners += listener
    }

    fun remove(listener: Listener) {
        listeners -= listener
    }

    fun onScrollChanged(view: View, t: Int, oldt: Int) {
        if (listeners.isEmpty()) return
        val dy = t - oldt
        if (dy == 0) return
        for (i in listeners.indices.reversed()) listeners[i].onScroll(view, dy)
    }
}
