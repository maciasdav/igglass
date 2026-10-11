package com.maciasdxv.igglass

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Localiza la barra de pestañas por su ID de recurso y la convierte en una
 * cápsula de vidrio flotante con una píldora de selección deslizable.
 *
 * Anclas (Instagram 450), todas por nombre de recurso con getIdentifier, así
 * que sobreviven a que cambie el número del ID:
 *  - `tab_bar` y sus pestañas `feed_tab`, `clips_tab`, `direct_tab`,
 *    `search_tab`, `profile_tab`.
 *  - `tab_bar_shadow`: la línea de 1px sobre la barra, que se oculta.
 *  - `layout_container_center_right_coordinator_layout`: el contenedor que deja
 *    libre la franja de navegación del sistema con un margen inferior.
 *
 * Instagram coloca el feed con un margen inferior igual al alto de la barra y
 * todo el contenedor con un margen igual a la franja del sistema. Para que la
 * barra flote, ambos márgenes se ponen a 0 y la barra se sube esa franja.
 */
internal object TabBarGlass {

    private const val IG_PACKAGE = "com.instagram.android"
    private val TAB_NAMES = listOf("feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab")

    /** Reintentos mientras Instagram aún no ha inflado o medido la barra. */
    private const val MAX_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 300L
    private const val MAX_CANCELLED_FRAMES = 3

    /** Barras ya modificadas. Débil: no retiene vistas de Activities muertas. */
    private val installed = WeakHashMap<View, Unit>()

    fun attach(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        tryAttach(decor, MAX_ATTEMPTS)
    }

    private fun tryAttach(decor: View, attemptsLeft: Int) {
        val barId = idOf(decor, "tab_bar")
        if (barId == 0) {
            GlassLog.w("no existe el recurso tab_bar en esta versión de Instagram")
            return
        }
        val bar = decor.findViewById<View>(barId) as? ViewGroup
        if (bar == null || bar.width == 0) {
            if (attemptsLeft > 0) {
                decor.postDelayed({ safe("reintento barra") { tryAttach(decor, attemptsLeft - 1) } }, RETRY_DELAY_MS)
            }
            // Sin barra en esta Activity (login, modales...): no es un error.
            return
        }
        if (installed.containsKey(bar)) return
        installed[bar] = Unit
        Installation(bar).install()
    }

    /** Todo lo que se le añade a una barra concreta, y cómo se mantiene. */
    private class Installation(private val bar: ViewGroup) {

        private val density = bar.resources.displayMetrics.density
        private val tabs = TAB_NAMES.mapNotNull { name ->
            val id = idOf(bar, name)
            if (id == 0) null else bar.findViewById<View>(id)?.let { name to it }
        }
        private val tabViews = tabs.map { it.second }

        /** El color con el que Instagram pinta la barra; marca el tema claro u oscuro. */
        private var themeColor = (bar.background as? ColorDrawable)?.color ?: Color.BLACK

        private val wrapper = bar.parent as? FrameLayout
        private val shadow = idOf(bar, "tab_bar_shadow").takeIf { it != 0 }
            ?.let { id -> wrapper?.findViewById<View>(id) }
        private val coordinator = findAncestor("layout_container_center_right_coordinator_layout")

        /** Alto original de la barra: el margen con el que Instagram aparta el contenido. */
        private val barSpace = bar.layoutParams?.height?.takeIf { it > 0 } ?: bar.height
        /** Alto de la franja de navegación del sistema que Instagram reserva. */
        private var navInset = (coordinator?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

        private lateinit var pill: GlassPillDrawable
        private val press = if (GlassParams.FLOATING_BAR) BarPressEffect(bar) else null
        private var capsule: CapsuleGlassDrawable? = null
        private var videoBlur: VideoBlur? = null
        private var dragView: PillDragView? = null

        private val floating = GlassParams.FLOATING_BAR && wrapper != null

        /** Vistas a las que Instagram aparta del borde inferior con un margen igual a [barSpace]. */
        private val spacedSiblings = wrapper?.let { w ->
            (0 until w.indexOfChild(bar)).mapNotNull { w.getChildAt(it) }.filter { child ->
                // Los ViewStub no ocupan espacio: corregirlos solo costaría frames.
                child !== shadow && child !is ViewStub &&
                    (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin == barSpace
            }
        }.orEmpty()

        private var windowObserver: ViewTreeObserver? = null

        /** Frames cancelados seguidos; límite para no dejar la pantalla sin dibujar. */
        private var cancelledFrames = 0

        /** Scroll acumulado en la dirección actual, para decidir encoger o expandir. */
        private var scrollAccum = 0

        /** Desplazamientos de las listas que hay detrás de la barra. */
        private val scrollListener = ScrollWatcher.Listener { view, dy -> onContentScroll(view, dy) }

        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            var draw = true
            safe("frame barra") { draw = onPreDraw() }
            draw
        }

        fun install() {
            if (tabs.isEmpty()) {
                GlassLog.w("tab_bar encontrada pero sin pestañas reconocibles")
                return
            }
            logTabStates()

            pill = GlassPillDrawable(bar, tabViews, selectedIndex() ?: 0) { themeColor }
            pill.motion.stepDp = tabStepPx() / density
            pill.setBounds(0, 0, bar.width, bar.height)
            bar.overlay.add(pill)
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                safe("layout píldora") {
                    pill.setBounds(0, 0, v.width, v.height)
                    pill.motion.stepDp = tabStepPx() / density
                    pill.invalidateSelf()
                }
            }

            if (floating) {
                val capsule = CapsuleGlassDrawable(bar, ::backdropSources, { themeColor }, press)
                this.capsule = capsule
                videoBlur = VideoBlur(bar, capsule::capsuleRect)
                registerLayoutRules()
                enforceLayout()
            }
            if (GlassParams.PILL_DRAG && wrapper != null) {
                dragView = PillDragView(bar.context, bar, tabViews, pill.motion, press).also {
                    wrapper.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }

            if (press != null && floating && GlassParams.COMPACT_ON_SCROLL) ScrollWatcher.add(scrollListener)
            windowObserver = bar.viewTreeObserver
            bar.viewTreeObserver.addOnPreDrawListener(preDraw)
            bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    safe("desmontar barra") { uninstall() }
                }
            })
            GlassLog.i(
                "barra instalada (${bar.width}x${bar.height}, ${tabs.size} pestañas, " +
                    "flotante=$floating, arrastre=${dragView != null}, franja sistema=${navInset}px)",
            )
        }

        private fun uninstall() {
            // El observador de la ventana donde se instaló; al desmontar, bar.viewTreeObserver ya es otro.
            windowObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            ScrollWatcher.remove(scrollListener)
            coordinator?.let(LayoutGuard::unregister)
            LayoutGuard.unregister(bar)
            spacedSiblings.forEach(LayoutGuard::unregister)
            pill.motion.stop()
            press?.stop()
            videoBlur?.release()
            bar.overlay.remove(pill)
            installed.remove(bar)
            // Esto se ejecuta dentro de dispatchDetachedFromWindow del contenedor:
            // quitarle un hijo ahora deja un hueco en el array que está recorriendo
            // y Android lanza un NullPointerException (pasaba al cambiar de tema).
            // Se quita después, y solo si la barra no ha vuelto a montarse.
            val drag = dragView ?: return
            Handler(Looper.getMainLooper()).post {
                safe("quitar capa de arrastre") {
                    if (!bar.isAttachedToWindow) (drag.parent as? ViewGroup)?.removeView(drag)
                }
            }
        }

        /** False cancela este frame: el layout cambió y hay que medir de nuevo antes de dibujar. */
        private fun onPreDraw(): Boolean {
            follow()
            val capsule = capsule ?: return true
            // Instagram vuelve a poner su fondo al cambiar de tema: recuperar el color y reemplazarlo.
            if (bar.background !== capsule) {
                (bar.background as? ColorDrawable)?.let { themeColor = it.color }
                bar.background = capsule
            }
            // Respaldo de LayoutGuard: si algo cambió los márgenes por otro camino,
            // corregirlos y saltar este frame para que no se vea el layout intermedio.
            if (enforceLayout() && cancelledFrames < MAX_CANCELLED_FRAMES) {
                cancelledFrames++
                return false
            }
            cancelledFrames = 0
            // Litho monta la interfaz de cada reel al deslizar (sin layout) y puede
            // reiniciar sus propiedades al remontarla: revisarla en cada frame.
            if (GlassParams.REELS_LIFT_UI && currentTab() == "clips_tab") {
                safe("interfaz de reels") { liftReelsUi() }
            }
            capsule.updateBackdrop()
            videoBlur?.update()
            return true
        }

        /**
         * Encoger al bajar y volver al subir. Solo cuentan las listas que están
         * detrás de la barra (no carruseles horizontales ni otras ventanas).
         */
        private fun onContentScroll(view: View, dy: Int) {
            val press = press ?: return
            if (nativeLayout() || noCompactTab() || !bar.isShown || !isBehindBar(view)) return
            // Cambio de dirección: empezar a acumular de cero.
            if ((dy > 0) != (scrollAccum > 0)) scrollAccum = 0
            scrollAccum += dy
            if (scrollAccum > GlassParams.COMPACT_TRIGGER_DP * density) {
                press.setCompact(true)
            } else if (scrollAccum < -GlassParams.EXPAND_TRIGGER_DP * density) {
                press.setCompact(false)
            }
        }

        private fun currentTab(): String? = selectedIndex()?.let { tabs[it].first }

        private fun noCompactTab(): Boolean {
            val index = selectedIndex() ?: return false
            return tabs[index].first in GlassParams.NO_COMPACT_TABS
        }

        /**
         * Reels: el video llega hasta abajo, pero su interfaz está anclada al borde
         * inferior de la página y quedaría bajo la cápsula. Se sube con translationY
         * (que también mueve su zona de toque) hasta quedar encima de la cápsula.
         * Se busca en todas las páginas cargadas (la actual y las vecinas).
         */
        private fun liftReelsUi() {
            val pager = reelsPager ?: findById(bar.rootView, "clips_viewer_view_pager")?.also { reelsPager = it }
            if (pager == null || !pager.isAttachedToWindow) {
                reelsPager = null
                return
            }
            // Cuánto invade la página la zona de la barra (sin las transformaciones de la barra).
            pager.getLocationInWindow(loc)
            val pageBottom = loc[1] + pager.height
            (bar.parent as View).getLocationInWindow(loc)
            val barTop = loc[1] + bar.top
            val lift = (pageBottom - barTop + GlassParams.REELS_UI_GAP_DP * density).coerceAtLeast(0f)

            val targets = ArrayList<View>()
            val videos = ArrayList<View>()
            val gradients = ArrayList<View>()
            collectReelsUi(pager, targets, videos, gradients)
            for (view in targets) {
                if (view.translationY != -lift) view.translationY = -lift
            }
            gradients.forEach { stretchGradient(it, lift) }
            if (GlassParams.REELS_FILL_VIDEO) videos.forEach(::fillPage)
        }

        /**
         * Instagram da al contenedor del video el alto del video a lo ancho de la
         * pantalla y lo centra en la página. Si es algo más bajo que la página, se
         * agranda (alto de la página, ancho proporcional) y los lados quedan fuera
         * de pantalla. Tiene que ser por layout: la superficie del video la pinta el
         * compositor con el tamaño de la vista e ignora escalas de dibujo.
         */
        private fun fillPage(video: View) {
            val page = video.parent as? View ?: return
            val lp = video.layoutParams ?: return
            if (page.width <= 0 || page.height <= 0) return
            // El alto que puso Instagram: el actual, salvo que sea el que pusimos nosotros.
            val applied = filledVideos[video]
            val original = if (applied != null && lp.height == applied.first) applied.second else lp.height
            if (original <= 0) return
            val scale = page.height.toFloat() / original
            if (scale <= 1.001f || scale > GlassParams.REELS_FILL_MAX_SCALE) return
            val width = (page.width * scale).roundToInt()
            if (lp.width != width || lp.height != page.height) {
                lp.width = width
                lp.height = page.height
                video.layoutParams = lp
                filledVideos[video] = page.height to original
            }
        }

        /** Videos agrandados: alto aplicado → alto original de Instagram. */
        private val filledVideos = WeakHashMap<View, Pair<Int, Int>>()

        private var reelsPager: View? = null
        private val loc = IntArray(2)
        private val reelsIds by lazy {
            mapOf(
                // Columna de botones + descripción: se sube su contenedor común.
                idOf(bar, "clips_ufi_component") to 1,
                // Barra de reproducción: se sube el contenedor Litho que la aloja, que
                // también dibuja la fila de botones de abajo (crear, plantillas...).
                idOf(bar, "scrubber") to 2,
            ).filterKeys { it != 0 }
        }

        private val videoContainerId by lazy { idOf(bar, "clips_video_container") }
        private val gradientId by lazy { idOf(bar, "clips_bottom_legibility_gradient_component") }

        /**
         * El degradado que oscurece el pie del video para que se lea el texto.
         * Si subiera con el texto quedaría flotando sobre la cápsula; en vez de
         * eso se queda pegado al borde inferior y se alarga hacia arriba [lift] px.
         */
        private fun stretchGradient(view: View, lift: Float) {
            val h = view.height
            if (h <= 0) return
            val scale = (h + lift) / h
            if (view.translationY != 0f) view.translationY = 0f
            if (view.pivotY != h.toFloat()) view.pivotY = h.toFloat()
            if (view.scaleY != scale) view.scaleY = scale
        }

        /** Recorre el pager de Reels y junta las vistas de interfaz a elevar y los videos. */
        private fun collectReelsUi(
            view: View,
            out: MutableList<View>,
            videos: MutableList<View>,
            gradients: MutableList<View>,
        ) {
            if (view.id == videoContainerId && videoContainerId != 0) {
                videos += view
                return
            }
            if (view.id == gradientId && gradientId != 0) {
                gradients += view
                return
            }
            val up = reelsIds[view.id]
            if (up != null) {
                var target: View = view
                repeat(up) { (target.parent as? View)?.let { target = it } }
                if (target !in out) out += target
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectReelsUi(it, out, videos, gradients) }
            }
        }

        private fun findById(root: View, name: String): View? {
            val id = idOf(bar, name).takeIf { it != 0 } ?: return null
            return root.findViewById(id)
        }

        private fun isBehindBar(view: View): Boolean {
            val sources = backdropSources()
            var v: Any? = view
            while (v is View) {
                if (v === bar) return false
                if (sources.any { it === v }) return true
                v = v.parent
            }
            return false
        }

        /** En las pestañas de [GlassParams.NATIVE_LAYOUT_TABS] se deja el layout de Instagram. */
        private fun nativeLayout(): Boolean {
            val index = selectedIndex() ?: return false
            return tabs[index].first in GlassParams.NATIVE_LAYOUT_TABS
        }

        /** Alto de la barra agrandada a la cápsula. */
        private val capsuleHeight = (GlassParams.CAPSULE_HEIGHT_DP * density).toInt()

        private fun coordinatorMargin() = if (nativeLayout()) navInset else 0
        private fun barMargin() = if (nativeLayout()) 0 else navInset

        /** En modo nativo el contenido acaba [GlassParams.NATIVE_OVERLAP_DP] por debajo del borde de la cápsula. */
        private fun siblingMargin() =
            if (nativeLayout()) (capsuleHeight - (GlassParams.NATIVE_OVERLAP_DP * density).toInt()).coerceAtLeast(0) else 0

        private fun registerLayoutRules() {
            coordinator?.let {
                LayoutGuard.register(it, LayoutGuard.Rule(::coordinatorMargin) { incoming ->
                    // Instagram pone aquí el alto de la franja del sistema: tomarlo como referencia.
                    if (incoming > 0) navInset = incoming
                })
            }
            LayoutGuard.register(bar, LayoutGuard.Rule(::barMargin))
            spacedSiblings.forEach { LayoutGuard.register(it, LayoutGuard.Rule(::siblingMargin)) }
        }

        /**
         * Lleva los márgenes a lo que pide el modo actual (flotante o nativo).
         * Solo toca lo que difiere; devuelve true si cambió algo.
         */
        private fun enforceLayout(): Boolean {
            var changed = false
            fun fix(view: View?, want: Int) {
                val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (lp.bottomMargin == want) return
                GlassLog.i("corrijo: ${LayoutGuard.nameOf(view)} mar.bottom ${lp.bottomMargin} -> $want")
                lp.bottomMargin = want
                view.layoutParams = lp
                changed = true
            }
            (coordinator?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
                ?.takeIf { it > 0 && it != navInset && !nativeLayout() }
                ?.let { navInset = it }
            fix(coordinator, coordinatorMargin())
            fix(bar, barMargin())
            bar.layoutParams?.let { lp ->
                if (lp.height != capsuleHeight) {
                    GlassLog.i("corrijo: tab_bar alto ${lp.height} -> $capsuleHeight")
                    lp.height = capsuleHeight
                    bar.layoutParams = lp
                    changed = true
                }
            }
            spacedSiblings.forEach { fix(it, siblingMargin()) }

            if (shadow != null && shadow.visibility == View.VISIBLE) {
                shadow.visibility = View.INVISIBLE
                changed = true
            }
            val padH = ((GlassParams.CAPSULE_MARGIN_H_DP + GlassParams.CAPSULE_PADDING_H_DP) * density).toInt()
            // Las pestañas conservan el alto original de la barra: centrarlas en la cápsula.
            val padV = ((capsuleHeight - barSpace) / 2).coerceAtLeast(0)
            if (bar.paddingLeft != padH || bar.paddingRight != padH ||
                bar.paddingTop != padV || bar.paddingBottom != padV
            ) {
                bar.setPadding(padH, padV, padH, padV)
                changed = true
            }
            return changed
        }

        /** Lo que queda detrás de la barra: los hermanos anteriores a ella. */
        private fun backdropSources(): List<View> {
            val wrapper = wrapper ?: return emptyList()
            val barIndex = wrapper.indexOfChild(bar)
            return (0 until barIndex).mapNotNull { wrapper.getChildAt(it) }
                .filter { it !== shadow && it.visibility == View.VISIBLE }
        }

        /** Si la pestaña activa cambió, desliza la píldora hasta ella. */
        private fun follow() {
            val index = selectedIndex() ?: return
            if (index == pill.motion.index) return
            GlassLog.i("píldora -> ${tabs[index].first}")
            pill.motion.animateTo(index)
            // Una pestaña nueva siempre muestra la barra completa.
            press?.setCompact(false)
            scrollAccum = 0
        }

        private fun selectedIndex(): Int? =
            tabViews.indexOfFirst(::isSelected).takeIf { it >= 0 }

        private fun tabStepPx(): Float =
            if (tabViews.size < 2) 0f else tabViews[1].left.toFloat() - tabViews[0].left

        private fun findAncestor(name: String): View? {
            val id = idOf(bar, name).takeIf { it != 0 } ?: return null
            var parent = bar.parent
            while (parent is View) {
                if (parent.id == id) return parent
                parent = parent.parent
            }
            return null
        }

        /** Volcado único para saber cómo marca Instagram la pestaña activa. */
        private fun logTabStates() {
            tabs.forEach { (name, tab) ->
                GlassLog.i("pestaña $name sel=${tab.isSelected} act=${tab.isActivated}")
            }
        }
    }

    /** Instagram puede marcar la pestaña activa en el contenedor o en el icono. */
    private fun isSelected(view: View): Boolean {
        if (view.isSelected || view.isActivated) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                if (child.isSelected || child.isActivated) return true
            }
        }
        return false
    }

    private fun idOf(view: View, name: String): Int =
        view.resources.getIdentifier(name, "id", IG_PACKAGE)
}
