package com.example.inbetween

import kotlin.math.max

/**
 * Chooses a coarser, exact-multiple grid interval as the canvas zooms out.
 *
 * The selected interval keeps visible grid lines at least ~8 screen pixels apart
 * and limits each axis to roughly 180 lines. Multipliers follow 1, 2, 5, 10, 20...
 * so every displayed line remains aligned to the document's original grid.
 */
internal fun adaptiveGridStep(
    baseStep: Int,
    screenScale: Float,
    spanX: Float,
    spanY: Float
): Float {
    val base = baseStep.coerceAtLeast(1).toFloat()
    if (!screenScale.isFinite() || screenScale <= 0f) return base

    val spacingMultiplier = 8f / (base * screenScale)
    val countMultiplier = max(spanX, spanY).coerceAtLeast(0f) / (base * 180f)
    val required = max(1f, max(spacingMultiplier, countMultiplier))

    var multiplier = 1f
    while (multiplier < required && multiplier < 1_000_000f) {
        multiplier *= when (multiplier) {
            1f -> 2f
            2f -> 2.5f
            else -> 2f
        }
    }
    return base * multiplier
}
