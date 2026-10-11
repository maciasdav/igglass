package com.maciasdxv.igglass

import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.sign

/**
 * La reacción de la barra al dedo, como en iOS 26:
 *  - al presionar, la barra crece un poco y se aclara bajo el dedo;
 *  - mientras el dedo se mueve, la barra lo sigue con resistencia de goma:
 *    cuanto más se aleja, más se desplaza y se estira, sin llegar a soltarse;
 *  - al soltar, todo vuelve a su sitio con un pequeño rebote.
 *
 * También lleva el encogido por scroll ([setCompact]): la barra se reduce
 * hacia abajo, manteniendo fijo su borde inferior.
 *
 * Se aplica con escala y traslación de la propia vista de la barra, así que
 * cápsula, iconos y píldora se mueven juntos. Solo se tocan esas propiedades
 * mientras hay algo que animar; en reposo quedan como las dejó Instagram.
 */
internal class BarPressEffect(private val bar: View) {

    private val density = bar.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** 0 en reposo, 1 presionada. Lo lee la cápsula para el brillo. */
    var amount = 0f
        private set

    /** Dónde está el dedo, en coordenadas de la barra. */
    var touchX = 0f
        private set
    var touchY = 0f
        private set

    private val press = Spring(PRESS_STIFFNESS, PRESS_DAMPING)
    private val shiftX = Spring(SHIFT_STIFFNESS, SHIFT_DAMPING)
    private val shiftY = Spring(SHIFT_STIFFNESS, SHIFT_DAMPING)
    private val compact = Spring(COMPACT_STIFFNESS, COMPACT_DAMPING)

    /** Si la barra está encogida (o yendo hacia ello). */
    val isCompact: Boolean
        get() = compact.target == 1.0

    /** Valores de Instagram al empezar, para sumarles el efecto y devolverlos al acabar. */
    private var baseTx = 0f
    private var baseTy = 0f
    private var baseSx = 1f
    private var baseSy = 1f

    private var active = false
    private var running = false
    private var lastFrameNanos = -1L
    private val frameCallback = Choreographer.FrameCallback { now -> safe("frame presión") { onFrame(now) } }

    fun down(x: Float, y: Float) {
        activate()
        // Tocar la barra encogida la devuelve a su tamaño.
        compact.target = 0.0
        touchX = x
        touchY = y
        press.target = 1.0
        shiftX.target = 0.0
        shiftY.target = 0.0
        start()
    }

    /**
     * [x],[y]: el dedo en coordenadas de la barra. [dx],[dy]: cuánto se ha
     * movido desde que tocó, en px de pantalla.
     */
    fun move(x: Float, y: Float, dx: Float, dy: Float) {
        if (!active) return
        touchX = x
        touchY = y
        shiftX.target = rubberBand(dx, dp(GlassParams.PRESS_MAX_SHIFT_X_DP)).toDouble()
        shiftY.target = rubberBand(dy, dp(GlassParams.PRESS_MAX_SHIFT_Y_DP)).toDouble()
        start()
    }

    fun setCompact(value: Boolean) {
        val target = if (value) 1.0 else 0.0
        if (compact.target == target) return
        if (value) activate()
        compact.target = target
        start()
    }

    /** Guarda los valores de Instagram la primera vez que el efecto toma la barra. */
    private fun activate() {
        if (active) return
        baseTx = bar.translationX
        baseTy = bar.translationY
        baseSx = bar.scaleX
        baseSy = bar.scaleY
        active = true
    }

    fun up() {
        press.target = 0.0
        shiftX.target = 0.0
        shiftY.target = 0.0
        start()
    }

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = -1L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        if (!running) return
        val dt = if (lastFrameNanos < 0) 0.0 else (now - lastFrameNanos) / 1e9
        lastFrameNanos = now
        press.step(dt)
        shiftX.step(dt)
        shiftY.step(dt)
        compact.step(dt)
        if (!active) {
            running = false
            return
        }
        apply()
        val settled = press.settled && shiftX.settled && shiftY.settled && compact.settled
        if (settled && press.target == 0.0 && compact.target == 0.0) {
            // Devolver la barra exactamente como estaba.
            active = false
            amount = 0f
            bar.translationX = baseTx
            bar.translationY = baseTy
            bar.scaleX = baseSx
            bar.scaleY = baseSy
            bar.invalidate()
            running = false
            return
        }
        if (settled) {
            running = false
            return
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun apply() {
        amount = press.value.toFloat().coerceIn(0f, 1.2f)
        val sx = shiftX.value.toFloat()
        val sy = shiftY.value.toFloat()
        val maxX = dp(GlassParams.PRESS_MAX_SHIFT_X_DP)
        val maxY = dp(GlassParams.PRESS_MAX_SHIFT_Y_DP)
        // Crece al presionar y se estira en el eje hacia el que tira el dedo.
        val grow = GlassParams.PRESS_SCALE * press.value.toFloat()
        val stretchX = GlassParams.PRESS_STRETCH * (abs(sx) / maxX)
        val stretchY = GlassParams.PRESS_STRETCH_Y * (abs(sy) / maxY)
        // Encogida: escala menor y el borde inferior en su sitio (+ una pequeña caída).
        val c = compact.value.toFloat()
        val shrink = 1f - (1f - GlassParams.COMPACT_SCALE) * c
        val keepBottom = bar.height * (1f - shrink) / 2f + dp(GlassParams.COMPACT_DROP_DP) * c
        bar.translationX = baseTx + sx
        bar.translationY = baseTy + sy + keepBottom
        bar.scaleX = baseSx * shrink * (1f + grow + stretchX - stretchY * 0.5f)
        bar.scaleY = baseSy * shrink * (1f + grow + stretchY - stretchX * 0.25f)
        // El brillo bajo el dedo vive en el fondo de la barra.
        bar.invalidate()
    }

    /** Resorte 1D con el mismo integrador que la píldora (subpasos de 1/240 s). */
    private class Spring(private val stiffness: Double, private val damping: Double) {
        var value = 0.0
        var velocity = 0.0
        var target = 0.0

        val settled: Boolean
            get() = abs(value - target) < 0.001 && abs(velocity) < 0.01

        fun step(dt: Double) {
            var t = dt
            while (t > 0) {
                val h = if (t > 1 / 240.0) 1 / 240.0 else t
                velocity += (-stiffness * (value - target) - damping * velocity) * h
                value += velocity * h
                t -= h
            }
            if (settled) {
                value = target
                velocity = 0.0
            }
        }
    }

    private companion object {
        /** Presión: rápida y apenas sin rebote. */
        const val PRESS_STIFFNESS = 500.0
        const val PRESS_DAMPING = 36.0

        /** Desplazamiento: más blando, con rebote al soltar. */
        const val SHIFT_STIFFNESS = 260.0
        const val SHIFT_DAMPING = 18.0

        /** Encogido: suave, sin rebote apreciable. */
        const val COMPACT_STIFFNESS = 220.0
        const val COMPACT_DAMPING = 28.0

        /**
         * Resistencia de goma (la de iOS): crece casi lineal al principio y se
         * acerca a [max] sin llegar nunca, por lejos que vaya el dedo.
         */
        fun rubberBand(distance: Float, max: Float): Float {
            if (max <= 0f) return 0f
            val x = abs(distance) / max
            return sign(distance) * max * (1f - 1f / (x * 0.55f + 1f))
        }
    }
}
