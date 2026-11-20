package com.maciasdxv.igglass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Capa invisible encima de la barra que permite arrastrar la píldora.
 *
 * Todo toque sobre la barra pasa por aquí y se reenvía tal cual a la barra,
 * así que tocar, mantener pulsado o tocar dos veces siguen funcionando como en
 * Instagram. Solo cuando el dedo se desliza a lo largo de la barra (en
 * horizontal; en vertical en el riel de las tablets) más allá del umbral
 * del sistema, la barra recibe un CANCEL y el gesto pasa a mover la píldora.
 * Al soltar, si la píldora quedó en otra pestaña, se simula un toque en ella:
 * Instagram cambia de pestaña por su camino normal.
 *
 * Al soltar, basta con haber pasado un 30 % del camino a la siguiente pestaña
 * para cambiar a ella. Más allá de la primera o la última pestaña la píldora
 * se resiste: solo avanza un tercio de lo que se mueve el dedo. La posición se
 * calcula con los centros reales de las pestañas, así que la píldora queda
 * bajo el dedo aunque estén repartidas de forma desigual.
 */
@SuppressLint("ViewConstructor")
internal class PillDragView(
    context: Context,
    private val bar: ViewGroup,
    private val tabs: List<View>,
    /** Orientación de la barra y posición de sus pestañas. */
    private val shape: BarShape,
    private val motion: PillMotion,
    /** Reacción de toda la barra al dedo (crecer, brillo, seguirlo); null = sin ella. */
    private val press: BarPressEffect?,
) : View(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hitRect = Rect()
    private val inverse = Matrix()
    private val point = FloatArray(2)

    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startIndex = 0
    private var lastHapticTab = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = try {
        handle(event)
    } catch (t: Throwable) {
        GlassLog.w("fallo en el arrastre de la píldora", t)
        tracking = false
        dragging = false
        false
    }

    private fun handle(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bar.isShown || !overBar(event)) return false
                tracking = true
                dragging = false
                downX = event.x
                downY = event.y
                toBar(event.x, event.y)
                press?.down(point[0], point[1])
                forward(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                toBar(event.x, event.y)
                press?.move(point[0], point[1], event.x - downX, event.y - downY)
                if (!dragging) {
                    val along = along(event)
                    val across = if (shape.vertical) event.x - downX else event.y - downY
                    if (abs(along) > slop && abs(along) > abs(across) && tabs.size > 1) {
                        beginDrag(event)
                    } else {
                        forward(event)
                    }
                }
                if (dragging) dragTo(along(event))
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                press?.up()
                if (dragging) endDrag(along(event)) else forward(event)
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                press?.up()
                if (dragging) {
                    dragging = false
                    motion.release(startIndex)
                } else {
                    forward(event)
                }
            }

            else -> if (tracking && !dragging) forward(event)
        }
        return true
    }

    private fun beginDrag(event: MotionEvent) {
        dragging = true
        // La barra deja de ver el gesto: ningún botón queda pulsado.
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        forward(cancel)
        cancel.recycle()
        // Que el contenedor de Instagram (deslizar a cámara/mensajes) no nos lo quite.
        parent?.requestDisallowInterceptTouchEvent(true)
        startIndex = motion.index
        lastHapticTab = startIndex
        motion.startDrag()
    }

    /** Cuánto se movió el dedo desde que tocó, a lo largo de la barra. */
    private fun along(event: MotionEvent) = if (shape.vertical) event.y - downY else event.x - downX

    /** Posición en pasos de pestaña con el dedo desplazado [delta] px desde que tocó. */
    private fun fingerPosition(delta: Float): Float = shape.positionAt(shape.center(startIndex) + delta)

    private fun dragTo(delta: Float) {
        val raw = fingerPosition(delta)
        val last = tabs.lastIndex.toFloat()
        val position = when {
            raw < 0f -> raw * EDGE_RESISTANCE
            raw > last -> last + (raw - last) * EDGE_RESISTANCE
            else -> raw
        }
        motion.dragTo(position)

        val approximateTab = position.coerceIn(0f, last).roundToInt()
        if (approximateTab != lastHapticTab) {
            bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticTab = approximateTab
        }
    }

    private fun endDrag(delta: Float) {
        dragging = false
        val ratio = fingerPosition(delta) - startIndex
        val shift = when {
            ratio > SWITCH_THRESHOLD -> maxOf(1, ratio.roundToInt())
            ratio < -SWITCH_THRESHOLD -> minOf(-1, ratio.roundToInt())
            else -> 0
        }
        val newIndex = (startIndex + shift).coerceIn(0, tabs.lastIndex)
        motion.release(newIndex)
        if (newIndex != startIndex) tapTab(tabs[newIndex])
    }

    /** Un toque sintético en el centro de [tab], entregado a la barra. */
    private fun tapTab(tab: View) {
        shape.centerOf(tab, point)
        val x = point[0]
        val y = point[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        bar.dispatchTouchEvent(down)
        bar.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * Reenvía [event] a la barra, pasado a sus coordenadas. Igual que hace
     * ViewGroup: restar la posición y deshacer la transformación (la barra
     * puede estar desplazada o escalada por [press]).
     */
    private fun forward(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation((left - bar.left).toFloat(), (top - bar.top).toFloat())
        if (!bar.matrix.isIdentity) {
            bar.matrix.invert(inverse)
            copy.transform(inverse)
        }
        bar.dispatchTouchEvent(copy)
        copy.recycle()
    }

    /** Pasa un punto de esta capa a coordenadas de la barra; queda en [point]. */
    private fun toBar(x: Float, y: Float) {
        point[0] = x + left - bar.left
        point[1] = y + top - bar.top
        if (!bar.matrix.isIdentity) {
            bar.matrix.invert(inverse)
            inverse.mapPoints(point)
        }
    }

    private fun overBar(event: MotionEvent): Boolean {
        bar.getHitRect(hitRect)
        return hitRect.contains((event.x + left).toInt(), (event.y + top).toInt())
    }

    private companion object {
        const val SWITCH_THRESHOLD = 0.3f
        const val EDGE_RESISTANCE = 0.33f
    }
}
