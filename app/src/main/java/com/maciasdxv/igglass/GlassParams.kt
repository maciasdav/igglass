package com.maciasdxv.igglass

/**
 * Todos los parámetros ajustables del efecto, en un solo sitio.
 *
 * Los de la física de la píldora (resortes) están en PillMotion.
 * Cambiar un valor = recompilar e instalar; más adelante pasarán a
 * XSharedPreferences para ajustarlos sin recompilar.
 */
internal object GlassParams {

    // --- Barra flotante (cápsula de vidrio) ----------------------------------

    /**
     * true: el feed pasa por debajo de la barra y la barra es una cápsula de
     * vidrio flotante. false: se deja el fondo y el layout de Instagram.
     */
    const val FLOATING_BAR = true

    /**
     * Pestañas en las que se deja el layout original de Instagram: el contenido
     * no pasa por debajo de la barra. Vacío: Reels también es flotante y su
     * interfaz se eleva sobre la cápsula (ver [REELS_LIFT_UI]).
     */
    val NATIVE_LAYOUT_TABS = emptySet<String>()

    /**
     * En Reels el video llega hasta abajo y la interfaz del reel (botones,
     * descripción, barra de reproducción) sube para quedar encima de la cápsula.
     */
    const val REELS_LIFT_UI = true

    /**
     * Reels más bajos que la página (pantallas 20:9) se escalan desde el centro
     * hasta llenarla, recortando los lados, en vez de dejar franjas negras.
     */
    const val REELS_FILL_VIDEO = true

    /**
     * Escala máxima para llenar: un reel 9:16 en pantalla 20:9 necesita ~1.2.
     * Videos cuadrados u horizontales necesitarían mucho más y se dejan con franjas.
     */
    const val REELS_FILL_MAX_SCALE = 1.3f

    /** Separación entre la interfaz elevada del reel y la cápsula. */
    const val REELS_UI_GAP_DP = 4f

    /** Pestañas en las que la barra no se encoge al hacer scroll. */
    val NO_COMPACT_TABS = setOf("clips_tab")

    /** Permite arrastrar la píldora con el dedo entre pestañas. */
    const val PILL_DRAG = true

    // --- Reacción de la barra al dedo ----------------------------------------

    /** Cuánto crece la barra al presionarla (0.02 = 2 %). */
    const val PRESS_SCALE = 0.02f

    /**
     * Desenfoque del compositor sobre los videos (SurfaceView), que la captura
     * normal no puede ver. 0 = desactivado: el video se ve nítido bajo la cápsula.
     */
    const val VIDEO_BLUR_RADIUS_DP = 16f

    /** Opacidad máxima del brillo bajo el dedo. */
    const val PRESS_GLOW_ALPHA = 0.25f

    /** Radio del brillo bajo el dedo. */
    const val PRESS_GLOW_RADIUS_DP = 64f

    /** Lo máximo que la barra sigue al dedo (nunca se alcanza: resistencia de goma). */
    const val PRESS_MAX_SHIFT_X_DP = 5f
    const val PRESS_MAX_SHIFT_Y_DP = 2.5f

    // --- Encoger al hacer scroll ----------------------------------------------

    /** Al hacer scroll hacia abajo la barra se encoge; hacia arriba vuelve. */
    const val COMPACT_ON_SCROLL = true

    /** Tamaño de la barra encogida (0.86 = 86 %). */
    const val COMPACT_SCALE = 0.86f

    /** Cuánto baja además la barra encogida. */
    const val COMPACT_DROP_DP = 4f

    /** Scroll hacia abajo acumulado para encoger. */
    const val COMPACT_TRIGGER_DP = 24f

    /** Scroll hacia arriba acumulado para volver al tamaño normal. */
    const val EXPAND_TRIGGER_DP = 12f

    /** Estiramiento extra en el eje del tirón, al acercarse al máximo (0.015 = 1.5 %). */
    const val PRESS_STRETCH = 0.015f

    /** Lo mismo en vertical, más contenido (0.006 = 0.6 %). */
    const val PRESS_STRETCH_Y = 0.006f

    /**
     * Alto de la cápsula (iOS: ~54dp). La barra de Instagram mide 47dp, así que
     * se agranda. En las pestañas nativas (Reels) la diferencia queda sobre el video.
     */
    const val CAPSULE_HEIGHT_DP = 54f

    /** Separación entre la cápsula y los lados de la pantalla (iOS: ~21dp). */
    const val CAPSULE_MARGIN_H_DP = 21f

    /** Separación entre los bordes de la cápsula y la primera/última pestaña. */
    const val CAPSULE_PADDING_H_DP = 0f

    /** Opacidad del contorno fino y uniforme de la cápsula (0 = sin contorno). */
    const val CAPSULE_EDGE_ALPHA = 0.12f

    /**
     * En las pestañas nativas (Reels), cuánto pasa el video por debajo de la
     * cápsula. Más de ~15dp empieza a tapar la línea de tiempo del video.
     */
    const val NATIVE_OVERLAP_DP = 12f

    /** Fracción de resolución a la que se captura y procesa el fondo */
    const val CAPSULE_RESOLUTION_SCALE = 0.33f

    /** Desenfoque del contenido detrás de la cápsula */
    const val CAPSULE_BLUR_DP = 8f

    /** Saturación del fondo: 1 = original, 1.5 = colores más vivos */
    const val CAPSULE_SATURATION = 1.5f

    /** Lente del borde de la cápsula. */
    const val CAPSULE_LENS_HEIGHT_DP = 24f
    const val CAPSULE_LENS_AMOUNT_DP = 24f

    /**
     * Opacidad del velo de color sobre la cápsula. El color es
     * casi negro en tema oscuro y casi blanco en tema claro.
     */
    const val CAPSULE_TINT_ALPHA = 0.4f

    /** Lo mismo en tema claro: el vidrio de iOS es bastante más blanco en claro. */
    const val CAPSULE_TINT_ALPHA_LIGHT = 0.65f

    // --- Geometría de la píldora ---------------------------------------------

    /** Margen entre la píldora y los lados de su pestaña (iOS: ~4dp). */
    const val PILL_INSET_H_DP = 4f

    /** Margen entre la píldora y el borde superior/inferior de la barra (iOS: ~4dp). */
    const val PILL_INSET_V_DP = 4f

    /**
     * Cuánto crece en alto al levantarse. Más de 2 × [PILL_INSET_V_DP]
     * la saca de la barra y se recorta.
     */
    const val PILL_LIFT_DP = 6f

    /**
     * true: la píldora se vuelve vidrio con lente al moverse.
     * false: píldora lisa de relleno uniforme, como en Instagram para iOS.
     */
    const val PILL_GLASS = false

    // --- Refracción (shader de lente) ----------------------------------------

    /** Ancho de la franja del borde que curva la imagen (0 = sin lente). */
    const val LENS_HEIGHT_DP = 12f

    /** Cuánto se desplaza la imagen en el borde: la fuerza de la refracción. */
    const val LENS_AMOUNT_DP = 14f

    /** Separa los colores en el borde, como un prisma. */
    const val CHROMATIC_ABERRATION = true

    /** Suma una curvatura radial hacia el centro (efecto "gota"). */
    const val DEPTH_EFFECT = false

    // --- Brillo del borde (shader de highlight) ------------------------------

    /** Grosor del borde brillante. */
    const val RIM_WIDTH_DP = 0.5f

    /** Opacidad del brillo (0..1). */
    const val RIM_ALPHA = 0.5f

    /** De dónde viene la luz, en grados (45 = arriba a la izquierda y abajo a la derecha). */
    const val RIM_ANGLE_DEG = 45f

    /** Qué tan rápido se apaga el brillo lejos de la dirección de la luz (1 = lineal). */
    const val RIM_FALLOFF = 1f

    // --- Sombra --------------------------------------------------------------

    /** Difuminado de la sombra bajo la píldora. */
    const val SHADOW_BLUR_DP = 6f

    /** Desplazamiento vertical de la sombra. */
    const val SHADOW_OFFSET_Y_DP = 2f

    /** Opacidad de la sombra (0 = sin sombra). */
    const val SHADOW_ALPHA = 0.3f

    // --- Tinte ---------------------------------------------------------------

    /**
     * Opacidad del relleno de la píldora en reposo. Se desvanece mientras es
     * vidrio y vuelve al aterrizar. El color se elige contra el fondo de la
     * barra: blanco sobre barra oscura, negro sobre barra clara.
     */
    const val TINT_ALPHA = 0.16f

    /** Relleno de la píldora en tema claro (negro sobre la cápsula blanca). */
    const val TINT_ALPHA_LIGHT = 0.10f
}
