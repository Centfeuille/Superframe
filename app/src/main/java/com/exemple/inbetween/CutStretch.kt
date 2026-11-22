package com.example.inbetween

import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cut settings > "Stretch the image": scales the whole drawing from the old Cut size to the new one (about the page origin,
 * so the old frame exactly fills the new one). Call it BEFORE the Cut's own size is replaced. Not undoable.
 *
 *  - Drawing / background layers: a region-transform stroke (kind 2) covering the old frame is appended to each drawing and its
 *    tiles are rebuilt, the same replay the selection Transform uses (the pixels are resampled once).
 *  - Vector layers: every point, the line weight and the live-fill seed are scaled (weight by the geometric mean of both factors).
 *  - Reference images (not the screen-pinned ones): their matrices are scaled.
 *  - Camera keys and frame size, Transformation folder offsets, media offsets: scaled.
 *  Perspective guides are left alone.
 */
fun stretchCutContent(cut: Cut, oldW: Int, oldH: Int, newW: Int, newH: Int) {
    if (oldW <= 0 || oldH <= 0) return
    val sx = newW / oldW.toFloat(); val sy = newH / oldH.toFloat()
    if (abs(sx - 1f) < 1e-4f && abs(sy - 1f) < 1e-4f) return
    val area = sqrt(sx * sy)
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()) // drawings shared by linked cels are scaled once
    val frameRuns = IntArray(oldH * 3).also { for (y in 0 until oldH) { it[y * 3] = y; it[y * 3 + 1] = 0; it[y * 3 + 2] = oldW } } // (y, x0, x1) of the old frame
    val scale = floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)
    fun scaleKeys(keys: SnapshotStateList<CamKey>, f: Float) { for (i in keys.indices) { val k = keys[i]; keys[i] = CamKey(k.t, k.v * f, k.interp, k.ease) } }
    for (layer in cut.layers.toList()) {
        if (layer.isGroup) {
            if (layer.transformFolder) { scaleKeys(layer.transformX, sx); scaleKeys(layer.transformY, sy) }
            continue
        }
        when {
            layer.kind == 2 -> layer.cam?.let { c ->
                scaleKeys(c.xs, sx); scaleKeys(c.ys, sy)
                c.w = (c.w * sx).roundToInt().coerceIn(16, 8192); c.h = (c.h * sy).roundToInt().coerceIn(16, 8192)
            }
            layer.kind == 5 -> {
                if (!layer.refPinned) for (cel in layer.cels) {
                    if (!seen.add(cel.strokes)) continue
                    for (k in cel.strokes) {
                        val m = k.m ?: continue
                        if (k.kind != 3 || m.size < 6) continue
                        m[0] *= sx; m[1] *= sx; m[2] *= sx; m[3] *= sy; m[4] *= sy; m[5] *= sy
                    }
                }
                layer.imageRevision++
            }
            layer.kind == 6 || layer.kind == 7 -> { layer.mediaX *= sx; layer.mediaY *= sy; layer.mediaScale *= area }
            layer.kind == 3 -> {}
            layer.vector -> for (cel in layer.cels.toList()) {
                if (!seen.add(cel.strokes)) continue
                val strokes = cel.vectorStrokes()
                if (strokes.isEmpty()) continue
                val scaled = strokes.map { s ->
                    s.copy(
                        points = s.points.map { p -> p.copy(x = p.x * sx, y = p.y * sy, elevationOffset = p.elevationOffset * area) },
                        baseWidth = s.baseWidth * area, wobble = s.wobble * area, fillSeedX = s.fillSeedX * sx, fillSeedY = s.fillSeedY * sy
                    )
                }
                swapVectorCel(layer, cel, scaled)
            }
            layer.kind == 0 || layer.kind == 4 -> for (cel in layer.cels) {
                if (cel.strokes.isEmpty() || !seen.add(cel.strokes)) continue
                cel.strokes.add(Ink(FloatArray(0), 0, Brush(""), 2, frameRuns, scale.copyOf()))
                TileCache.invalidate(cel.strokes) // rebuilt by replaying the strokes, the new region transform included
            }
        }
    }
    cut.invalidateStructureCaches()
}
