package com.example.inbetween

import android.graphics.Matrix
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
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

/** (y, x0, x1) selection / region runs moved by (dx, dy). */
private fun shiftRuns(r: IntArray?, dx: Int, dy: Int): IntArray? {
    if (r == null) return null
    val o = r.copyOf(); var i = 0
    while (i + 2 < o.size) { o[i] += dy; o[i + 1] += dx; o[i + 2] += dx; i += 3 }
    return o
}

/** A copy of [k] moved by (dx, dy) in document space; Ink is immutable, so every kind is rebuilt. Kinds: 0 stroke, 1 fill, 2 region transform, 3 image, 4 region warp. */
private fun shiftInk(k: Ink, dx: Int, dy: Int): Ink = when (k.kind) {
    0 -> {
        val p = k.p.copyOf(); var i = 0
        while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += 4 } // (x, y, width scale, alpha scale)
        Ink(p, k.color, k.b, k.kind, shiftRuns(k.runs, dx, dy), k.m, k.data, k.seed)
    }
    1 -> Ink(k.p, k.color, k.b, k.kind, shiftRuns(k.runs, dx, dy), k.m, k.data, k.seed)
    2 -> {
        // The lifted pixels move with the page: M' = T(d) * M * T(-d)
        val m = k.m?.takeIf { it.size >= 9 }?.let { v ->
            val mt = Matrix(); mt.setValues(v); mt.preTranslate(-dx.toFloat(), -dy.toFloat()); mt.postTranslate(dx.toFloat(), dy.toFloat())
            FloatArray(9).also { mt.getValues(it) }
        } ?: k.m
        Ink(k.p, k.color, k.b, k.kind, shiftRuns(k.runs, dx, dy), m, k.data, k.seed)
    }
    3 -> {
        val m = k.m?.copyOf()
        if (m != null && m.size >= 6) { m[2] += dx; m[5] += dy } // image -> document matrix: translation column
        Ink(k.p, k.color, k.b, k.kind, k.runs, m, k.data, k.seed)
    }
    4 -> {
        val m = k.m?.copyOf() // [cols, rows, vertices (x, y)...] in document space
        if (m != null) { var i = 2; while (i + 1 < m.size) { m[i] += dx; m[i + 1] += dy; i += 2 } }
        Ink(k.p, k.color, k.b, k.kind, shiftRuns(k.runs, dx, dy), m, k.data, k.seed)
    }
    else -> k
}

/**
 * Cut settings > the left / top handles: the page's top-left corner moved by (-dx, -dy), so every piece of content moves by (+dx, +dy) in the new
 * document coordinates (dx / dy > 0: the Cut grew on that side; < 0: it was cropped, what is left of the frame stays in the unbounded area). Call it
 * BEFORE the Cut's own size is replaced ([oldW] x [oldH] = size now, [newW] x [newH] = size after). Not undoable: the caller clears the history.
 *
 *  - Drawing / background layers: every ink is rebuilt moved (strokes, fills, region transforms, warps, images) and its tiles are rebuilt. Brush grain
 *    is anchored to the page, so a shift that is not a multiple of the 128 px texture tile moves the grain relative to the drawing.
 *  - Vector layers: every point, live-fill seed and absolute guide joint moves.
 *  - Reference images (not the screen-pinned ones) and the perspective guides' intersection: moved.
 *  - Camera keys move by (dx, dy); a Camera without keys stays centred on the Cut.
 *  - Transformation folders, video / audio media offsets: these are measured from the Cut centre, which moved by (dx, dy) - (new centre - old centre);
 *    the folder keys are corrected at each key's own scale and rotation (exact while those do not change between keys).
 */
fun shiftCutContent(cut: Cut, dx: Int, dy: Int, oldW: Int, oldH: Int, newW: Int, newH: Int) {
    if (dx == 0 && dy == 0) return
    val fx = dx.toFloat(); val fy = dy.toFloat()
    val ex = oldW / 2f + fx - newW / 2f; val ey = oldH / 2f + fy - newH / 2f // old centre, carried along, minus the new centre
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()) // drawings shared by linked cels are moved once
    fun shiftKeys(keys: SnapshotStateList<CamKey>, f: Float) { for (i in keys.indices) { val k = keys[i]; keys[i] = CamKey(k.t, k.v + f, k.interp, k.ease) } }
    fun folderKeys(layer: Layer, keys: SnapshotStateList<CamKey>, xAxis: Boolean) {
        for (i in keys.indices) {
            val k = keys[i]; val tr = layer.transformAt(k.t)
            val r = gRot(Offset(ex, ey) * tr.scale, tr.rotation)
            keys[i] = CamKey(k.t, k.v + (if (xAxis) ex - r.x else ey - r.y), k.interp, k.ease)
        }
    }
    for (layer in cut.layers.toList()) {
        if (layer.isGroup) {
            if (layer.transformFolder) { folderKeys(layer, layer.transformX, true); folderKeys(layer, layer.transformY, false) }
            continue
        }
        when {
            layer.kind == 2 -> layer.cam?.let { c -> shiftKeys(c.xs, fx); shiftKeys(c.ys, fy) }
            layer.kind == 5 -> {
                if (!layer.refPinned) for (cel in layer.cels) {
                    if (!seen.add(cel.strokes)) continue
                    for (k in cel.strokes) {
                        val m = k.m ?: continue
                        if (k.kind != 3 || m.size < 6) continue
                        m[2] += fx; m[5] += fy
                    }
                }
                layer.imageRevision++
            }
            layer.kind == 6 || layer.kind == 7 -> { layer.mediaX += ex; layer.mediaY += ey }
            layer.kind == 3 -> layer.persp?.let { it.center += fx; it.horizon += fy }
            layer.vector -> {
                for (cel in layer.cels.toList()) {
                    if (!seen.add(cel.strokes)) continue
                    val strokes = cel.vectorStrokes()
                    if (strokes.isEmpty()) continue
                    val moved = strokes.map { s -> s.copy(points = s.points.map { p -> p.copy(x = p.x + fx, y = p.y + fy) }, fillSeedX = s.fillSeedX + fx, fillSeedY = s.fillSeedY + fy) }
                    swapVectorCel(layer, cel, moved)
                }
                // Guide joints of the interpolation settings are absolute document positions (relative ones are offsets and stay).
                for (cel in layer.cels) cel.vecCfg = cel.vecCfg?.let { cfg ->
                    if (cfg.guideJoints.none { !it.rel }) cfg else cfg.copy(guideJoints = cfg.guideJoints.map { j -> if (j.rel) j else j.copy(x = j.x + fx, y = j.y + fy) })
                }
            }
            layer.kind == 0 || layer.kind == 4 -> for (cel in layer.cels) {
                if (cel.strokes.isEmpty() || !seen.add(cel.strokes)) continue
                val moved = cel.strokes.map { shiftInk(it, dx, dy) }
                cel.strokes.clear(); cel.strokes.addAll(moved)
                TileCache.invalidate(cel.strokes) // rebuilt by replaying the moved strokes
            }
        }
    }
    cut.invalidateStructureCaches()
}
