package com.example.inbetween

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Mesh deformation of a lifted selection (Warp and Puppet Warp of the selection transform).
 * A WarpMesh covers the lifted bitmap with (cols + 1) x (rows + 1) vertices in document space. Vertex (i, j) shows the bitmap point
 * (i / cols, j / rows), so Canvas.drawBitmapMesh draws the deformed picture. Both tools only move vertices; a committed deformation is
 * stored as an ink of kind 4 (the selection runs + [cols, rows, vertices...]) and replayed by TileSet.drawMeshTiles.
 */

/** A Puppet Warp pin: [px], [py] where it was placed, [qx], [qy] where it is now (document space, before the transform matrix). */
class Pin(var px: Float, var py: Float, var qx: Float, var qy: Float)

class WarpMesh(val cols: Int, val rows: Int, val verts: FloatArray) {
    fun copy() = WarpMesh(cols, rows, verts.copyOf())
    fun idx(i: Int, j: Int) = (j * (cols + 1) + i) * 2

    fun bounds(): RectF {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var k = 0
        while (k < verts.size) { x0 = min(x0, verts[k]); x1 = max(x1, verts[k]); y0 = min(y0, verts[k + 1]); y1 = max(y1, verts[k + 1]); k += 2 }
        return RectF(x0, y0, x1, y1)
    }

    /** The surface point at (u, v) in [0, 1] x [0, 1], bilinear inside the mesh cell; written to out[0], out[1]. */
    fun sample(u: Float, v: Float, out: FloatArray) {
        val gx = (u * cols).coerceIn(0f, cols.toFloat()); val gy = (v * rows).coerceIn(0f, rows.toFloat())
        val i = min(gx.toInt(), cols - 1); val j = min(gy.toInt(), rows - 1)
        val fx = gx - i; val fy = gy - j
        val a = idx(i, j); val b = idx(i + 1, j); val c = idx(i, j + 1); val d = idx(i + 1, j + 1)
        for (k in 0..1) out[k] = (1f - fx) * (1f - fy) * verts[a + k] + fx * (1f - fy) * verts[b + k] + (1f - fx) * fy * verts[c + k] + fx * fy * verts[d + k]
    }

    /** The same surface with another number of cells; the deformation done so far is kept. */
    fun resampled(c: Int, r: Int): WarpMesh {
        if (c == cols && r == rows) return copy()
        val out = FloatArray((c + 1) * (r + 1) * 2); val s = FloatArray(2)
        for (j in 0..r) for (i in 0..c) {
            sample(i / c.toFloat(), j / r.toFloat(), s)
            val o = (j * (c + 1) + i) * 2; out[o] = s[0]; out[o + 1] = s[1]
        }
        return WarpMesh(c, r, out)
    }

    /** A copy with every vertex carried through [m] (the transform matrix is baked into the mesh when it is committed). */
    fun mapped(m: Matrix): WarpMesh { val v = verts.copyOf(); m.mapPoints(v); return WarpMesh(cols, rows, v) }

    /** [cols, rows, vertices...]: how the mesh is stored in Ink.m. */
    fun data(): FloatArray {
        val d = FloatArray(verts.size + 2); d[0] = cols.toFloat(); d[1] = rows.toFloat()
        System.arraycopy(verts, 0, d, 2, verts.size); return d
    }

    companion object {
        fun grid(left: Float, top: Float, w: Float, h: Float, c: Int, r: Int): WarpMesh {
            val v = FloatArray((c + 1) * (r + 1) * 2)
            for (j in 0..r) for (i in 0..c) { val o = (j * (c + 1) + i) * 2; v[o] = left + w * i / c; v[o + 1] = top + h * j / r }
            return WarpMesh(c, r, v)
        }

        fun fromData(d: FloatArray?): WarpMesh? {
            if (d == null || d.size < 2) return null
            val c = d[0].toInt(); val r = d[1].toInt()
            if (c < 1 || r < 1 || c > 256 || r > 256 || d.size != 2 + (c + 1) * (r + 1) * 2) return null
            return WarpMesh(c, r, d.copyOfRange(2, d.size))
        }
    }
}

/**
 * Grid warp nudge: node (ni, nj) of [start] moves by (dx, dy); the nodes around it follow with a smooth fall-off. [strength] 0..1 is the reach of the
 * nudge: 0 moves only the node, 1 carries the whole grid along. Always computed from the mesh at the start of the drag.
 */
fun nudgedMesh(start: WarpMesh, ni: Int, nj: Int, dx: Float, dy: Float, strength: Float): WarpMesh {
    val out = start.copy()
    val radius = 0.75f + strength.coerceIn(0f, 1f) * max(start.cols, start.rows).toFloat()
    for (j in 0..start.rows) for (i in 0..start.cols) {
        val d = hypot((i - ni).toFloat(), (j - nj).toFloat())
        if (d >= radius) continue
        val t = 1f - d / radius
        val w = t * t * (3f - 2f * t)
        val o = start.idx(i, j)
        out.verts[o] += dx * w; out.verts[o + 1] += dy * w
    }
    return out
}

/**
 * Puppet Warp: every vertex of [base] is moved by the pins with a moving-least-squares rigid deformation (the nearer a pin, the more it counts),
 * so the picture bends around pinned points and turns around them like a puppet instead of stretching. One pin only translates.
 */
fun puppetMesh(base: WarpMesh, pins: List<Pin>): WarpMesh {
    val out = base.copy()
    val n = pins.size
    if (n == 0) return out
    val w = FloatArray(n)
    val vs = base.verts
    var k = 0
    while (k < vs.size) {
        val vx = vs[k]; val vy = vs[k + 1]
        var exact = -1
        var sw = 0f; var psx = 0f; var psy = 0f; var qsx = 0f; var qsy = 0f
        for (i in 0 until n) {
            val dx = vx - pins[i].px; val dy = vy - pins[i].py; val d2 = dx * dx + dy * dy
            if (d2 < 1e-3f) { exact = i; break }
            val wi = 1f / d2
            w[i] = wi; sw += wi
            psx += wi * pins[i].px; psy += wi * pins[i].py; qsx += wi * pins[i].qx; qsy += wi * pins[i].qy
        }
        if (exact >= 0) { out.verts[k] = pins[exact].qx; out.verts[k + 1] = pins[exact].qy; k += 2; continue }
        psx /= sw; psy /= sw; qsx /= sw; qsy /= sw
        // best rotation about the weighted centres, as a unit complex number s = (sr, si)
        var sr = 0f; var si = 0f
        for (i in 0 until n) {
            val ax = pins[i].px - psx; val ay = pins[i].py - psy
            val bx = pins[i].qx - qsx; val by = pins[i].qy - qsy
            sr += w[i] * (ax * bx + ay * by); si += w[i] * (ax * by - ay * bx)
        }
        val mag = hypot(sr, si)
        val rx = vx - psx; val ry = vy - psy
        if (mag < 1e-6f) { out.verts[k] = qsx + rx; out.verts[k + 1] = qsy + ry } // one pin (or symmetric pins): plain translation
        else { sr /= mag; si /= mag; out.verts[k] = qsx + sr * rx - si * ry; out.verts[k + 1] = qsy + sr * ry + si * rx }
        k += 2
    }
    return out
}

/**
 * Selection runs (y, x0, x1 triples) as a clip path. Equal runs on consecutive rows are merged into one rectangle, so a rectangular
 * selection is a single rectangle instead of one per row.
 */
fun clipPathOf(runs: IntArray): Path {
    val path = Path()
    fun emit(key: Long, o: IntArray) {
        path.addRect((key shr 32).toInt().toFloat(), o[0].toFloat(), key.toInt().toFloat(), (o[1] + 1).toFloat(), Path.Direction.CW)
    }
    var cur = HashMap<Long, IntArray>() // rectangles still growing: (x0, x1) -> [first row, last row]
    var i = 0
    while (i + 2 < runs.size) {
        val y = runs[i]
        val next = HashMap<Long, IntArray>()
        while (i + 2 < runs.size && runs[i] == y) {
            val key = (runs[i + 1].toLong() shl 32) or (runs[i + 2].toLong() and 0xFFFFFFFFL)
            val open = cur.remove(key)
            if (open != null && open[1] == y - 1) { open[1] = y; next[key] = open }
            else { if (open != null) emit(key, open); next[key] = intArrayOf(y, y) }
            i += 3
        }
        for ((key, o) in cur) emit(key, o)
        cur = next
    }
    for ((key, o) in cur) emit(key, o)
    return path
}
