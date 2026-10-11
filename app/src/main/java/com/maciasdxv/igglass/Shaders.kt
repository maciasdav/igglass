/*
 * Shaders AGSL tomados de Kyant0/backdrop v2.0.0
 * (backdrop/src/.../internal/Shaders.kt).
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant, Apache License 2.0
 *
 * Sin cambios en la lógica; solo se añadieron comentarios.
 */
package com.maciasdxv.igglass

internal object Shaders {

    /**
     * Funciones de distancia firmada (SDF) de un rectángulo redondeado.
     * Negativo dentro de la forma, cero en el borde, positivo fuera.
     */
    private const val ROUNDED_RECT_SDF = """
// Radio de la esquina del cuadrante en el que cae `coord`.
// radii = (arriba-izq, arriba-der, abajo-der, abajo-izq).
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

// Distancia de `coord` (centrada) al borde de la forma.
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

// Dirección hacia fuera del borde más cercano (la "normal" de la superficie).
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

    /**
     * Lente: dentro de la franja `refractionHeight` junto al borde, desplaza
     * el punto que se muestrea siguiendo un perfil circular, como el canto
     * redondeado de un vidrio grueso. El centro se ve sin deformar.
     *
     * Uniforms:
     *  - content           la imagen a refractar (la barra capturada)
     *  - size              tamaño de la píldora en px
     *  - offset            desplazamiento si la captura tiene margen (aquí 0)
     *  - cornerRadii       radios de las 4 esquinas
     *  - refractionHeight  ancho de la franja curvada
     *  - refractionAmount  desplazamiento máximo (negativo = hacia dentro)
     *  - depthEffect       0/1, añade curvatura radial
     */
    const val REFRACTION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

// Perfil de un cuarto de círculo: 0 en el centro de la lente, 1 en el borde.
float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    // Lejos del borde: vidrio plano, sin deformación.
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

    /**
     * La misma lente con dispersión cromática: muestrea siete veces a lo
     * largo de la normal (rojo → violeta) y recompone el color. La
     * separación crece en las esquinas (producto x·y) y es cero en los ejes.
     *
     * Uniform extra:
     *  - chromaticAberration  intensidad de la dispersión (0..1)
     */
    const val REFRACTION_DISPERSION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    // Siete muestras desplazadas; cada una aporta a los canales de su color.
    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""

    /**
     * Brillo especular del borde: intensidad según cuánto mira la normal del
     * borde hacia la luz (`angle`). Se pinta en un trazo fino con mezcla PLUS,
     * así que ilumina lo que hay debajo en vez de taparlo. Brilla en los dos
     * lados opuestos a lo largo del eje de la luz.
     *
     * Uniforms: size, cornerRadii, color (del brillo), angle (radianes), falloff.
     */
    const val HIGHLIGHT = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""
}
