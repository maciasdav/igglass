package com.maciasdxv.igglass

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * El borde brillante de una forma de vidrio (Highlight.Default de Kyant0/backdrop):
 * un trazo fino teñido por [Shaders.HIGHLIGHT] y sumado (PLUS) a lo que hay debajo.
 */
internal class GlassRim(private val density: Float) {

    private val shader = RuntimeShader(Shaders.HIGHLIGHT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        blendMode = BlendMode.PLUS
        shader = this@GlassRim.shader
        val blur = GlassParams.RIM_WIDTH_DP * density / 2f
        if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    /**
     * Contorno fino del mismo brillo en todo el perímetro, como el de iOS.
     * Mismas condiciones que [draw]: lienzo ya recortado a [path].
     */
    fun drawEdge(canvas: Canvas, path: Path, alpha: Float) {
        if (alpha <= 0f) return
        edgePaint.alpha = (alpha * 255).roundToInt()
        edgePaint.strokeWidth = ceil(GlassParams.RIM_WIDTH_DP * density) * 2f
        canvas.drawPath(path, edgePaint)
    }

    /**
     * Dibuja el borde de [path] (una píldora de [w]×[h] con origen en 0,0).
     * El lienzo debe estar ya recortado a [path]: el trazo es del doble de
     * ancho y su mitad exterior queda fuera.
     */
    fun draw(canvas: Canvas, path: Path, w: Float, h: Float, radius: Float, alpha: Float) {
        if (GlassParams.RIM_WIDTH_DP <= 0f || alpha <= 0f) return
        paint.alpha = (alpha * 255).roundToInt()
        paint.strokeWidth = ceil(GlassParams.RIM_WIDTH_DP * density) * 2f
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("cornerRadii", floatArrayOf(radius, radius, radius, radius))
        shader.setColorUniform("color", Color.WHITE)
        shader.setFloatUniform("angle", Math.toRadians(GlassParams.RIM_ANGLE_DEG.toDouble()).toFloat())
        shader.setFloatUniform("falloff", GlassParams.RIM_FALLOFF)
        canvas.drawPath(path, paint)
    }
}
