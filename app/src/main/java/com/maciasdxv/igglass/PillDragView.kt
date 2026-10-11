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
 * Instagram. Solo cuando el dedo se desliza en horizontal más allá del umbral
 * del sistema, la barra recibe un CANCEL y el gesto pasa a mover la píldora.
 * Al soltar, si la píldora quedó en otra pestaña, se simula un toque en ella:
 * Instagram cambia de pestaña por su camino normal.
 *
 * Al soltar, basta con haber pasado un 30 % del camino a la siguiente pestaña
 * para cambiar a ella. Más allá de la primera o la última pestaña la píldora
 * se resiste: solo avanza un tercio de lo que se mueve el dedo.
 */
@SuppressLint("ViewConstructor")
internal class PillDragView(
    context: Context,
    private val bar: ViewGroup,
    private val tabs: List<View>,
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
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) && tabs.size > 1) {
                        beginDrag(event)
                    } else {
                        forward(event)
                    }
                }
                if (dragging) dragTo(event.x - downX)
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                press?.up()
                if (dragging) endDrag(event.x - downX) else forward(event)
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

    private fun dragTo(rawDx: Float) {
        val step = tabStep()
        if (step <= 0f) return
        val total = rawDx * direction()
        val offset = when {
            total > 0 && startIndex == tabs.lastIndex -> total * EDGE_RESISTANCE
            total < 0 && startIndex == 0 -> total * EDGE_RESISTANCE
            else -> total
        }
        val position = startIndex + offset / step
        motion.dragTo(position)

        val approximateTab = position.coerceIn(0f, tabs.lastIndex.toFloat()).roundToInt()
        if (approximateTab != lastHapticTab) {
            bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticTab = approximateTab
        }
    }

    private fun endDrag(rawDx: Float) {
        dragging = false
        val step = tabStep()
        val ratio = if (step > 0f) rawDx * direction() / step else 0f
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
        val x = tab.left + tab.width / 2f
        val y = tab.top + tab.height / 2f
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

    /** Distancia entre los centros de dos pestañas vecinas. */
    private fun tabStep(): Float =
        if (tabs.size < 2) 0f else abs((tabs[1].left + tabs[1].width / 2f) - (tabs[0].left + tabs[0].width / 2f))

    private fun direction() = if (bar.layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f

    private companion object {
        const val SWITCH_THRESHOLD = 0.3f
        const val EDGE_RESISTANCE = 0.33f
    }
}
