package com.maciasdxv.igglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * La píldora de selección, dibujada en el overlay de la barra de pestañas.
 *
 * En reposo es un relleno plano y tenue sobre la pestaña activa. Al cambiar
 * de pestaña, [motion] la levanta, la convierte en vidrio y la desliza hasta
 * la nueva; mientras viaja, cada frame:
 *  1. Graba el fondo de la barra, el relleno y las pestañas, solo bajo la
 *     píldora, en un RenderNode propio. Todo en GPU: nada de bitmaps, que
 *     fallarían con los bitmaps HARDWARE de Instagram.
 *  2. Aplica la lente AGSL a ese RenderNode con RenderEffect.
 *  3. Pinta sombra, la imagen refractada recortada a la píldora y el borde.
 *
 * El overlay se dibuja después de las pestañas, así que la píldora queda
 * encima de ellas sin cambiar el layout de Instagram ni capturarse a sí misma.
 */
internal class GlassPillDrawable(
    private val bar: ViewGroup,
    /** Las pestañas en orden; la posición de [motion] es un índice en esta lista. */
    private val tabs: List<View>,
    initialIndex: Int,
    /** El color original de la barra; decide si el relleno es claro u oscuro. */
    private val themeColor: () -> Int,
) : Drawable() {

    val motion = PillMotion(initialIndex) { invalidateSelf() }

    private val density = bar.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val contentNode = RenderNode("igglass-pill")
    private val shadowNode = RenderNode("igglass-pill-shadow")

    private val lensShader = RuntimeShader(
        if (GlassParams.CHROMATIC_ABERRATION) Shaders.REFRACTION_DISPERSION else Shaders.REFRACTION,
    )
    private val rim = GlassRim(density)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Rectángulo de la píldora este frame, en coordenadas de la barra. */
    private val pill = RectF()
    /** Forma de la píldora en coordenadas propias (0,0 arriba a la izquierda). */
    private val localPath = Path()
    /** La misma forma en coordenadas de la barra, para recortar la sombra. */
    private val barPath = Path()

    /** Tras el primer fallo, la píldora deja de dibujarse y la barra queda intacta. */
    private var failed = false

    override fun draw(canvas: Canvas) {
        if (failed || !canvas.isHardwareAccelerated) return
        try {
            drawPill(canvas)
        } catch (t: Throwable) {
            failed = true
            motion.stop()
            GlassLog.w("la píldora falló al dibujar; se desactiva", t)
        }
    }

    private fun drawPill(canvas: Canvas) {
        if (!bar.isShown || tabs.isEmpty()) return
        if (!layoutPill()) return
        val radius = pill.height() / 2f

        if (motion.isFlat || !GlassParams.PILL_GLASS) {
            // En reposo, o con la píldora lisa: solo el relleno, sin captura ni shaders.
            fillPaint.color = fillColor(1f)
            canvas.drawRoundRect(pill, radius, radius, fillPaint)
            return
        }

        val w = pill.width().roundToInt()
        val h = pill.height().roundToInt()
        if (w <= 0 || h <= 0) return
        localPath.rewind()
        localPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        barPath.rewind()
        barPath.addRoundRect(pill.left, pill.top, pill.left + w, pill.top + h, radius, radius, Path.Direction.CW)

        // 1. Captura: fondo de la barra, relleno que se desvanece y pestañas.
        contentNode.setPosition(0, 0, w, h)
        val rec = contentNode.beginRecording(w, h)
        try {
            rec.translate(-pill.left, -pill.top)
            drawBarContent(rec, radius)
        } finally {
            contentNode.endRecording()
        }

        // 2. Lente: el borde crece con el vidrio y la fuerza con el material.
        contentNode.setRenderEffect(
            lensEffect(
                w, h, radius,
                height = dp(GlassParams.LENS_HEIGHT_DP) * motion.glassPresence,
                amount = dp(GlassParams.LENS_AMOUNT_DP) * motion.material,
            ),
        )

        // Borde y sombra son lo primero que se pierde al aterrizar.
        val rimAlpha = motion.material * rimOf(motion.glassPresence)

        // 3. Sombra, solo por fuera de la píldora.
        if (GlassParams.SHADOW_ALPHA > 0f && rimAlpha > 0f) {
            drawShadow(canvas, w, h, radius, GlassParams.SHADOW_ALPHA * rimAlpha)
        }

        canvas.save()
        canvas.translate(pill.left, pill.top)
        canvas.clipPath(localPath)
        // 4. Vidrio refractado.
        canvas.drawRenderNode(contentNode)
        // 5. Borde brillante.
        rim.draw(canvas, localPath, w.toFloat(), h.toFloat(), radius, GlassParams.RIM_ALPHA * rimAlpha)
        canvas.restore()
    }

    /**
     * Coloca [pill] según el movimiento. Tamaño en reposo: la pestaña menos
     * los márgenes. Levantada: [GlassParams.PILL_LIFT_DP] más alta, con la
     * misma proporción. Encima se aplica la deformación por aceleración.
     */
    private fun layoutPill(): Boolean {
        val pos = motion.position.coerceIn(0f, (tabs.size - 1).toFloat())
        val i0 = floor(pos).toInt()
        val i1 = minOf(i0 + 1, tabs.size - 1)
        val f = pos - i0
        val a = tabs[i0]
        val b = tabs[i1]
        val centerX = lerp(a.left + a.width / 2f, b.left + b.width / 2f, f)
        val tabWidth = lerp(a.width.toFloat(), b.width.toFloat(), f)

        val restW = tabWidth - 2 * dp(GlassParams.PILL_INSET_H_DP)
        val restH = bar.height - 2 * dp(GlassParams.PILL_INSET_V_DP)
        if (restW <= 0f || restH <= 0f) return false
        val liftedH = restH + dp(GlassParams.PILL_LIFT_DP)
        val liftedW = liftedH * restW / restH

        val w = (restW + (liftedW - restW) * motion.liftX) * (1 + motion.deviation)
        val h = (restH + (liftedH - restH) * motion.liftY) * (1 - motion.deviation)
        val centerY = bar.height / 2f
        pill.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
        return true
    }

    /** Lo que la barra pinta debajo del overlay, más el relleno de la píldora. */
    private fun drawBarContent(canvas: Canvas, radius: Float) {
        bar.background?.draw(canvas)
        val fillFade = 1f - motion.material
        if (fillFade > 0f) {
            fillPaint.color = fillColor(fillFade)
            canvas.drawRoundRect(pill, radius, radius, fillPaint)
        }
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            canvas.save()
            canvas.translate(child.left + child.translationX, child.top + child.translationY)
            child.draw(canvas)
            canvas.restore()
        }
    }

    private fun lensEffect(w: Int, h: Int, radius: Float, height: Float, amount: Float): RenderEffect? {
        if (height <= 0f || amount <= 0f) return null
        lensShader.apply {
            setFloatUniform("size", w.toFloat(), h.toFloat())
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(radius, radius, radius, radius))
            setFloatUniform("refractionHeight", height)
            // Negativo: el borde muestrea hacia dentro, como en Kyant0/backdrop.
            setFloatUniform("refractionAmount", -amount)
            setFloatUniform("depthEffect", if (GlassParams.DEPTH_EFFECT) 1f else 0f)
            if (GlassParams.CHROMATIC_ABERRATION) setFloatUniform("chromaticAberration", 1f)
        }
        return RenderEffect.createRuntimeShaderEffect(lensShader, "content")
    }

    private fun drawShadow(canvas: Canvas, w: Int, h: Int, radius: Float, alpha: Float) {
        val blur = dp(GlassParams.SHADOW_BLUR_DP)
        val offsetY = dp(GlassParams.SHADOW_OFFSET_Y_DP)
        val margin = ceil(blur * 3f).toInt()
        shadowPaint.alpha = (alpha * 255).roundToInt()
        shadowNode.setPosition(0, 0, w + 2 * margin, h + 2 * margin)
        val rec = shadowNode.beginRecording()
        try {
            rec.drawRoundRect(
                margin.toFloat(), margin + offsetY,
                (margin + w).toFloat(), margin + h + offsetY,
                radius, radius, shadowPaint,
            )
        } finally {
            shadowNode.endRecording()
        }
        shadowNode.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))

        canvas.save()
        canvas.clipOutPath(barPath)
        canvas.translate(pill.left - margin, pill.top - margin)
        canvas.drawRenderNode(shadowNode)
        canvas.restore()
    }

    /** Blanco sobre barra oscura, negro sobre barra clara, a [GlassParams.TINT_ALPHA] × [fade]. */
    private fun fillColor(fade: Float): Int {
        val light = Color.luminance(themeColor()) > 0.5f
        val base = if (light) Color.BLACK else Color.WHITE
        val tint = if (light) GlassParams.TINT_ALPHA_LIGHT else GlassParams.TINT_ALPHA
        val alpha = (tint * fade * 255).roundToInt()
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /** El borde y la sombra se apagan antes que el vidrio: curva cuadrática. */
        fun rimOf(presence: Float) = presence.coerceIn(0f, 1f).let { it * it }
    }
}
