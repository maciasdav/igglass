package com.maciasdxv.igglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * La cápsula de vidrio flotante: sustituye al fondo opaco de la barra.
 *
 * Como fondo de la barra se dibuja debajo de las pestañas. Lo que muestra es
 * [backdrop]: el contenido de Instagram que queda detrás (el feed), grabado a
 * resolución reducida y pasado por desenfoque → saturación → lente. Esa
 * grabación se rehace en [updateBackdrop], llamado antes de cada frame; el
 * fondo de la barra solo referencia el RenderNode, así que no hace falta
 * redibujar la barra cuando el feed se mueve.
 */
internal class CapsuleGlassDrawable(
    private val bar: ViewGroup,
    /** Las vistas de Instagram que quedan detrás de la barra, de abajo arriba. */
    private val sources: () -> List<View>,
    /** El color original de la barra; decide el tinte claro u oscuro. */
    private val themeColor: () -> Int,
    /** La reacción al dedo; aporta el brillo bajo el dedo. */
    private val press: BarPressEffect?,
) : Drawable() {

    private val density = bar.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val scale = GlassParams.CAPSULE_RESOLUTION_SCALE
    private val backdrop = RenderNode("igglass-capsule")
    private val lensShader = RuntimeShader(Shaders.REFRACTION_DISPERSION)
    private val rim = GlassRim(density)
    private val tintPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barInverse = Matrix()

    /** La cápsula en coordenadas de la barra. */
    private val capsule = RectF()
    private val localPath = Path()

    private var effectW = -1
    private var effectH = -1

    private var failed = false

    val isDark: Boolean
        get() = Color.luminance(themeColor()) <= 0.5f

    /** Regraba el contenido de detrás. Se llama en cada OnPreDraw. */
    fun updateBackdrop() {
        if (failed) return
        try {
            record()
        } catch (t: Throwable) {
            failed = true
            GlassLog.w("la cápsula falló al capturar el fondo; queda opaca", t)
            invalidateSelf()
        }
    }

    private fun record() {
        if (!layoutCapsule()) return
        val w = ceil(capsule.width() * scale).toInt().coerceAtLeast(1)
        val h = ceil(capsule.height() * scale).toInt().coerceAtLeast(1)

        backdrop.setPosition(0, 0, w, h)
        val rec = backdrop.beginRecording(w, h)
        try {
            // Base: el color de fondo del tema, por si el contenido no cubre todo.
            rec.drawColor(themeColor())
            rec.scale(scale, scale)
            // Coordenadas de la cápsula → de la barra → del padre común.
            rec.translate(-capsule.left, -capsule.top)
            // La barra puede estar desplazada o escalada (presión del dedo): deshacer
            // esa transformación para que el vidrio muestre lo que tiene realmente detrás.
            bar.matrix.invert(barInverse)
            rec.concat(barInverse)
            rec.translate(-bar.left.toFloat(), -bar.top.toFloat())
            for (view in sources()) {
                rec.save()
                rec.translate(view.left + view.translationX, view.top + view.translationY)
                view.draw(rec)
                rec.restore()
            }
        } finally {
            backdrop.endRecording()
        }
        if (w != effectW || h != effectH) {
            backdrop.setRenderEffect(effectFor(w, h))
            effectW = w
            effectH = h
        }
    }

    /** Desenfoque, luego saturación, luego lente. */
    private fun effectFor(w: Int, h: Int): RenderEffect {
        val blurPx = dp(GlassParams.CAPSULE_BLUR_DP) * scale
        var effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)

        val saturation = ColorMatrix().apply { setSaturation(GlassParams.CAPSULE_SATURATION) }
        effect = RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturation)),
            effect,
        )

        val radius = h / 2f
        lensShader.apply {
            setFloatUniform("size", w.toFloat(), h.toFloat())
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(radius, radius, radius, radius))
            setFloatUniform("refractionHeight", dp(GlassParams.CAPSULE_LENS_HEIGHT_DP) * scale)
            setFloatUniform("refractionAmount", -dp(GlassParams.CAPSULE_LENS_AMOUNT_DP) * scale)
            setFloatUniform("depthEffect", 1f)
            setFloatUniform("chromaticAberration", 1f)
        }
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(lensShader, "content"),
            effect,
        )
    }

    /** La cápsula en coordenadas de la barra (copia). */
    fun capsuleRect(): RectF = RectF().also { if (layoutCapsule()) it.set(capsule) }

    /** La cápsula ocupa el alto de la barra, separada de los lados de la pantalla. */
    private fun layoutCapsule(): Boolean {
        val margin = dp(GlassParams.CAPSULE_MARGIN_H_DP)
        capsule.set(margin, 0f, bar.width - margin, bar.height.toFloat())
        return capsule.width() > 0f && capsule.height() > 0f
    }

    override fun draw(canvas: Canvas) {
        if (!layoutCapsule()) return
        val w = capsule.width()
        val h = capsule.height()
        val radius = h / 2f
        localPath.rewind()
        localPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.translate(capsule.left, capsule.top)
        canvas.clipPath(localPath)
        if (failed || !canvas.isHardwareAccelerated) {
            // Sin vidrio: cápsula sólida del color original.
            canvas.drawColor(themeColor())
        } else {
            canvas.save()
            canvas.scale(1f / scale, 1f / scale)
            canvas.drawRenderNode(backdrop)
            canvas.restore()
            val tint = if (isDark) 0xFF121212.toInt() else 0xFFFAFAFA.toInt()
            tintPaint.color = tint
            val tintAlpha = if (isDark) GlassParams.CAPSULE_TINT_ALPHA else GlassParams.CAPSULE_TINT_ALPHA_LIGHT
            tintPaint.alpha = (tintAlpha * 255).roundToInt()
            canvas.drawRect(0f, 0f, w, h, tintPaint)
            drawGlow(canvas)
            rim.drawEdge(canvas, localPath, GlassParams.CAPSULE_EDGE_ALPHA)
            rim.draw(canvas, localPath, w, h, radius, GlassParams.RIM_ALPHA)
        }
        canvas.restore()
    }

    /** Brillo radial bajo el dedo, proporcional a la presión. */
    private fun drawGlow(canvas: Canvas) {
        val press = press ?: return
        val alpha = GlassParams.PRESS_GLOW_ALPHA * press.amount.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val x = press.touchX - capsule.left
        val y = press.touchY - capsule.top
        val white = Color.WHITE
        glowPaint.shader = RadialGradient(
            x, y, dp(GlassParams.PRESS_GLOW_RADIUS_DP),
            Color.argb((alpha * 255).roundToInt(), Color.red(white), Color.green(white), Color.blue(white)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(glowPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
