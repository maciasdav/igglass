package com.maciasdxv.igglass

import android.animation.ValueAnimator
import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Movimiento de la píldora de selección. Las posiciones van en "pasos de
 * pestaña": 0 es la primera pestaña, 1 la segunda.
 *
 * Tres magnitudes, cada una con su resorte:
 *  - [position]: dónde está la píldora. Viaja hacia [index].
 *  - [liftX]/[liftY]: cuánto está levantada (0 en reposo, 1 levantada). Sube
 *    al empezar un viaje o un arrastre y baja al llegar, con algo de rebote.
 *  - [deviation]: estiramiento por velocidad. Cuanto más rápido va, más ancha
 *    y plana se dibuja; en reposo vuelve a 0.
 *
 * Los resortes se definen como en las animaciones de iOS: un tiempo de
 * respuesta (cuánto tarda, aproximadamente) y una amortiguación (1 = sin
 * rebote, menos de 1 = rebota).
 */
internal class PillMotion(initialIndex: Int, private val requestDraw: () -> Unit) {

    /** Dónde se dibuja la píldora, en pasos de pestaña. */
    var position = initialIndex.toFloat()
        private set

    /** Elevación horizontal: 0 en reposo, 1 levantada; puede pasarse un poco. */
    var liftX = 0f
        private set

    /** Elevación vertical; algo más amortiguada que la horizontal. */
    var liftY = 0f
        private set

    /** Cuánto material de vidrio hay, 0..1 (solo se usa con GlassParams.PILL_GLASS). */
    var material = 0f
        private set

    /** Estiramiento: ancho × (1 + esto), alto × (1 − esto). */
    var deviation = 0f
        private set

    /** Cuánto se ve como vidrio, 0..1 (solo se usa con GlassParams.PILL_GLASS). */
    var glassPresence = 0f
        private set

    /** True cuando está quieta y sin levantar: basta con pintar la píldora en reposo. */
    var isFlat = true
        private set

    /** La pestaña a la que va la píldora. */
    var index = initialIndex
        private set

    /** Un paso de pestaña en dp; convierte la velocidad a dp/s para el estiramiento. */
    var stepDp = 0f

    private val travel = Spring(TRAVEL_RESPONSE, TRAVEL_DAMPING, initialIndex.toDouble())
    private val liftH = Spring(LIFT_RESPONSE, LIFT_DAMPING_H, 0.0)
    private val liftV = Spring(LIFT_RESPONSE, LIFT_DAMPING_V, 0.0)
    private var stretch = 0.0

    private var dragging = false
    private var fingerTarget = 0.0

    private var running = false
    private var lastFrameNanos = -1L
    private val frameCallback = Choreographer.FrameCallback { now -> safe("frame píldora") { onFrame(now) } }

    /** Se eligió una pestaña: viajar hasta ella levantada. Repetir la actual no hace nada. */
    fun animateTo(next: Int) {
        if (next == index && !dragging) return
        // "Quitar animaciones" en el sistema: aterrizar al instante.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapTo(next)
            return
        }
        index = next
        travel.target = next.toDouble()
        lift(true)
        start()
    }

    /** Colocar la píldora en [target], en reposo. */
    fun snapTo(target: Int) {
        stop()
        index = target
        dragging = false
        travel.reset(target.toDouble())
        liftH.reset(0.0)
        liftV.reset(0.0)
        stretch = 0.0
        publish()
        isFlat = true
        requestDraw()
    }

    /** Un dedo tomó la píldora donde está. */
    fun startDrag() {
        dragging = true
        fingerTarget = travel.value
        travel.velocity = 0.0
        lift(ValueAnimator.areAnimatorsEnabled())
        start()
    }

    /** El dedo está sobre [position] pasos de pestaña. */
    fun dragTo(position: Float) {
        if (dragging) fingerTarget = position.toDouble()
    }

    /** El dedo se soltó: asentarse en [next], igual que tras un toque. */
    fun release(next: Int) {
        if (!dragging) return
        dragging = false
        index = next
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapTo(next)
            return
        }
        travel.target = next.toDouble()
        start()
    }

    /** Detener el bucle de frames (al desmontar la barra). */
    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
    }

    private fun lift(up: Boolean) {
        val target = if (up) 1.0 else 0.0
        liftH.target = target
        liftV.target = target
        if (up) isFlat = false
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = -1L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        if (!running) return
        val dt = if (lastFrameNanos < 0) 0.0 else min((now - lastFrameNanos) / 1e9, MAX_FRAME)
        lastFrameNanos = now
        val more = step(dt)
        requestDraw()
        if (more) Choreographer.getInstance().postFrameCallback(frameCallback) else running = false
    }

    /** Avanza todo [dt] segundos. False cuando ya no queda nada en movimiento. */
    private fun step(dt: Double): Boolean {
        if (dragging) {
            // Bajo el dedo: seguirlo con un retraso corto, sin resorte.
            val before = travel.value
            travel.value += (fingerTarget - travel.value) * (1 - exp(-dt / FINGER_LAG))
            travel.target = travel.value
            travel.velocity = if (dt > 0) (travel.value - before) / dt else 0.0
        } else {
            travel.step(dt)
            // Bajar cuando ya casi llegó.
            if (abs(travel.target - travel.value) < LAND_DISTANCE) lift(false)
        }
        liftH.step(dt)
        liftV.step(dt)

        // Estiramiento según la velocidad (en dp/s), suavizado para que no tiemble.
        val speedDp = abs(travel.velocity) * stepDp
        val wanted = min(speedDp * STRETCH_PER_DP_S, MAX_STRETCH)
        stretch += (wanted - stretch) * (1 - exp(-dt / STRETCH_SMOOTHING))

        val settled = !dragging && travel.isAtRest && liftH.isAtRest && liftV.isAtRest &&
            liftH.target == 0.0 && stretch < 0.0005
        if (settled) {
            travel.reset(travel.target)
            stretch = 0.0
        }
        publish()
        isFlat = settled
        return !settled
    }

    private fun publish() {
        position = travel.value.toFloat()
        liftX = liftH.value.toFloat()
        liftY = liftV.value.toFloat()
        deviation = stretch.toFloat()
        material = liftH.value.coerceIn(0.0, 1.0).toFloat()
        glassPresence = material
    }

    /**
     * Oscilador amortiguado: x'' = −ω²(x − objetivo) − 2ζω·x', con
     * ω = 2π / respuesta y ζ = amortiguación. Integrado en subpasos de 1/240 s.
     */
    private class Spring(response: Double, private val damping: Double, start: Double) {
        private val omega = 2 * PI / response
        var value = start
        var velocity = 0.0
        var target = start

        val isAtRest: Boolean
            get() = abs(value - target) < 0.001 && abs(velocity) < 0.005

        fun reset(to: Double) {
            value = to
            target = to
            velocity = 0.0
        }

        fun step(dt: Double) {
            var left = dt
            while (left > 0) {
                val h = min(left, SUBSTEP)
                val accel = -omega * omega * (value - target) - 2 * damping * omega * velocity
                velocity += accel * h
                value += velocity * h
                left -= h
            }
            if (isAtRest) {
                value = target
                velocity = 0.0
            }
        }
    }

    private companion object {
        /** Viaje entre pestañas: ~0.4 s, casi sin rebote. */
        const val TRAVEL_RESPONSE = 0.4
        const val TRAVEL_DAMPING = 0.85

        /** Elevación: rápida y con rebote; algo menos en vertical. */
        const val LIFT_RESPONSE = 0.3
        const val LIFT_DAMPING_H = 0.55
        const val LIFT_DAMPING_V = 0.7

        /** A esta distancia del destino (en pestañas) la píldora empieza a bajar. */
        const val LAND_DISTANCE = 0.12

        /** Retraso con el que sigue al dedo, en segundos. */
        const val FINGER_LAG = 0.04

        /** Estiramiento por cada dp/s de velocidad, y su máximo (12 %). */
        const val STRETCH_PER_DP_S = 0.00006
        const val MAX_STRETCH = 0.12
        const val STRETCH_SMOOTHING = 0.06

        const val SUBSTEP = 1 / 240.0
        /** Un frame muy largo (app en pausa) no debe lanzar la píldora. */
        const val MAX_FRAME = 0.1
    }
}
