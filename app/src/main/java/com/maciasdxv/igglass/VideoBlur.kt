package com.maciasdxv.igglass

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Vidrio sobre los videos de Instagram.
 *
 * Los videos se reproducen en SurfaceView: el compositor del sistema los pinta
 * en una capa aparte, debajo de la ventana, y la app solo deja un hueco
 * transparente. La captura de [CapsuleGlassDrawable] ve ese hueco, no el video,
 * así que bajo la cápsula el video se vería nítido.
 *
 * Para cada SurfaceView que queda bajo la cápsula se crea una capa hija del
 * compositor con la forma de la cápsula y blur de fondo: el compositor
 * desenfoca el video que tiene debajo, y el tinte y el borde de la cápsula se
 * pintan encima como siempre. Sobre el video no hay lente: el compositor solo
 * sabe desenfocar.
 *
 * Usa métodos ocultos de SurfaceControl (setEffectLayer, setBackgroundBlurRadius,
 * setCornerRadius). Si no existen, queda desactivado y el video se ve nítido.
 */
internal class VideoBlur(
    private val bar: View,
    /** La cápsula en coordenadas de la barra (sin transformar). */
    private val capsuleLocal: () -> RectF,
) {

    private class Layer(val parent: SurfaceControl, val child: SurfaceControl) {
        val crop = Rect()
        var visible = false
    }

    private val layers = WeakHashMap<SurfaceView, Layer>()
    private val density = bar.resources.displayMetrics.density

    private val capsuleWindow = RectF()
    private val crop = Rect()
    private val loc = IntArray(2)

    /** Tras el primer fallo se desactiva: el video vuelve a verse nítido, sin más. */
    private var failed = false

    /** Pone las capas de blur bajo la cápsula. Se llama en cada OnPreDraw. */
    fun update() {
        if (failed || !Hidden.available || GlassParams.VIDEO_BLUR_RADIUS_DP <= 0f) return
        try {
            updateLayers()
        } catch (t: Throwable) {
            failed = true
            GlassLog.w("el blur de video falló; se desactiva", t)
            release()
        }
    }

    private fun updateLayers() {
        // Videos que ya no existen: soltar sus capas.
        val alive = Registry.surfaces()
        layers.keys.filter { it !in alive }.forEach { sv -> layers.remove(sv)?.let(::release) }

        val root = bar.rootSurfaceControl ?: return
        if (!locateCapsule()) {
            hideAll(root)
            return
        }
        val radius = capsuleWindow.height() / 2f
        val blur = (GlassParams.VIDEO_BLUR_RADIUS_DP * density).roundToInt()

        val t = SurfaceControl.Transaction()
        var dirty = false
        for (sv in alive) {
            val parent = sv.surfaceControl?.takeIf {
                it.isValid && sv.isShown && sv.rootSurfaceControl === root
            }
            var layer = layers[sv]
            if (parent == null) {
                if (layer != null && layer.visible) {
                    t.setVisibility(layer.child, false)
                    layer.visible = false
                    dirty = true
                }
                continue
            }
            if (layer == null || layer.parent !== parent) {
                layer?.let { release(it) }
                layer = Layer(parent, Hidden.createEffectLayer(parent))
                layers[sv] = layer
            }

            // La cápsula en coordenadas de la superficie del video. Si el video
            // está escalado (Reels a pantalla completa), la capa hija hereda esa
            // escala: hay que deshacerla.
            sv.getLocationInWindow(loc)
            val (sx, sy) = scaleOf(sv)
            crop.set(
                ((capsuleWindow.left - loc[0]) / sx).roundToInt(), ((capsuleWindow.top - loc[1]) / sy).roundToInt(),
                ((capsuleWindow.right - loc[0]) / sx).roundToInt(), ((capsuleWindow.bottom - loc[1]) / sy).roundToInt(),
            )
            val overlaps = crop.intersects(0, 0, sv.width, sv.height)
            if (!overlaps) {
                if (layer.visible) {
                    t.setVisibility(layer.child, false)
                    layer.visible = false
                    dirty = true
                }
                continue
            }
            if (!layer.visible || crop != layer.crop) {
                t.setCrop(layer.child, crop)
                t.setLayer(layer.child, 1)
                Hidden.setCornerRadius(t, layer.child, radius / sy)
                Hidden.setBackgroundBlurRadius(t, layer.child, blur)
                t.setVisibility(layer.child, true)
                layer.crop.set(crop)
                layer.visible = true
                dirty = true
            }
        }
        if (dirty) root.applyTransactionOnDraw(t) else t.close()
    }

    /** Escala acumulada de [view] y sus antecesores. */
    private fun scaleOf(view: View): Pair<Float, Float> {
        var sx = 1f
        var sy = 1f
        var v: Any? = view
        while (v is View) {
            sx *= v.scaleX
            sy *= v.scaleY
            v = v.parent
        }
        return sx to sy
    }

    /** Quita todas las capas (al desmontar la barra). */
    fun release() {
        layers.values.forEach(::release)
        layers.clear()
    }

    private fun release(layer: Layer) {
        safe("liberar capa de blur") {
            SurfaceControl.Transaction().reparent(layer.child, null).apply()
            layer.child.release()
        }
    }

    private fun hideAll(root: android.view.AttachedSurfaceControl) {
        val visible = layers.values.filter { it.visible }
        if (visible.isEmpty()) return
        val t = SurfaceControl.Transaction()
        visible.forEach {
            t.setVisibility(it.child, false)
            it.visible = false
        }
        root.applyTransactionOnDraw(t)
    }

    /** La cápsula en coordenadas de ventana, con la transformación actual de la barra. */
    private fun locateCapsule(): Boolean {
        if (!bar.isShown) return false
        val parent = bar.parent as? View ?: return false
        capsuleWindow.set(capsuleLocal())
        if (capsuleWindow.isEmpty) return false
        val matrix: Matrix = bar.matrix
        if (!matrix.isIdentity) matrix.mapRect(capsuleWindow)
        parent.getLocationInWindow(loc)
        capsuleWindow.offset((loc[0] + bar.left).toFloat(), (loc[1] + bar.top).toFloat())
        return true
    }

    /** Las SurfaceView vivas de la app; las mantienen los hooks de [HookEntry]. */
    object Registry {
        private val attached = WeakHashMap<SurfaceView, Unit>()

        fun attached(sv: SurfaceView) {
            attached[sv] = Unit
        }

        fun detached(sv: SurfaceView) {
            attached.remove(sv)
        }

        fun surfaces(): List<SurfaceView> = attached.keys.toList()
    }

    /** Acceso por reflexión a los métodos ocultos del compositor. */
    private object Hidden {
        private val setEffectLayer: Method? = find(SurfaceControl.Builder::class.java, "setEffectLayer")
        private val setBlur: Method? = find(
            SurfaceControl.Transaction::class.java, "setBackgroundBlurRadius",
            SurfaceControl::class.java, Int::class.javaPrimitiveType!!,
        )
        private val setCorner: Method? = find(
            SurfaceControl.Transaction::class.java, "setCornerRadius",
            SurfaceControl::class.java, Float::class.javaPrimitiveType!!,
        )

        val available = (setEffectLayer != null && setBlur != null && setCorner != null).also {
            GlassLog.i("blur de video: ${if (it) "disponible" else "NO disponible (falta un método oculto)"}")
        }

        fun createEffectLayer(parent: SurfaceControl): SurfaceControl {
            val builder = SurfaceControl.Builder().setName("igglass-video-blur").setParent(parent)
            setEffectLayer!!.invoke(builder)
            return builder.build()
        }

        fun setBackgroundBlurRadius(t: SurfaceControl.Transaction, sc: SurfaceControl, radius: Int) {
            setBlur!!.invoke(t, sc, radius)
        }

        fun setCornerRadius(t: SurfaceControl.Transaction, sc: SurfaceControl, radius: Float) {
            setCorner!!.invoke(t, sc, radius)
        }

        private fun find(cls: Class<*>, name: String, vararg params: Class<*>): Method? = try {
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (t: Throwable) {
            GlassLog.w("método oculto $name no accesible: ${t.javaClass.simpleName}")
            null
        }
    }
}
