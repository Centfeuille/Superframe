package com.example.inbetween

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/*
 * Shared on-canvas transform handles (selection, camera, Transformation folder).
 * A box is four corners in document space: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
 * Handles: 0..3 corners (scale), 4 top, 5 right, 6 bottom, 7 left (stretch), 8 rotate (outside the corners), 9 move (inside).
 */
const val H_NONE = -1
const val H_ROT = 8
const val H_MOVE = 9

/** State of a handle drag in progress. ctx: 0 selection / transform, 1 camera, 2 Transformation folder. */
class GzState {
    var ctx = -1; var h = H_NONE
    var d0 = Offset.Zero
    var box0: List<Offset> = emptyList()
    var m0: Matrix? = null                       // selection: matrix at drag start
    var x0 = 0f; var y0 = 0f; var s0 = 1f; var r0 = 0f // camera / folder values at drag start
    var w0 = 0; var h0 = 0                       // camera frame size at drag start (Cut settings: pending Cut size)
    var ox0 = 0; var oy0 = 0                     // Cut settings: how far the page's left / top edge had been moved at drag start
    var snap: Snap? = null                       // undo snapshot (camera / folder)
    var folder: Layer? = null
    var reference0: ReferencePose = ReferencePose()
    var referenceCel: Cel? = null                // Out of Peg: the adjacent drawing being adjusted
    var referenceLayer: Layer? = null
    var referenceInk: Ink? = null
    var referenceMatrix0: FloatArray? = null
}

/** Result of a handle drag: [m] is the document-space matrix to post-multiply, [sx] / [sy] the stretch along the box axes, [dRot] degrees. */
class GOp(val m: Matrix, val sx: Float, val sy: Float, val dRot: Float)

fun Matrix.mapOf(o: Offset): Offset { val a = floatArrayOf(o.x, o.y); mapPoints(a); return Offset(a[0], a[1]) }

fun gRot(o: Offset, deg: Float): Offset {
    val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
    return Offset(o.x * c - o.y * s, o.x * s + o.y * c)
}

private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

private fun distToSeg(q: Offset, a: Offset, b: Offset): Float {
    val ab = b - a; val l2 = dot(ab, ab)
    val t = if (l2 < 1e-6f) 0f else (dot(q - a, ab) / l2).coerceIn(0f, 1f)
    return (q - (a + ab * t)).getDistance()
}

private fun inside(p: List<Offset>, q: Offset): Boolean {
    var pos = 0; var neg = 0
    for (i in 0..3) {
        val a = p[i]; val b = p[(i + 1) % 4]
        val c = (b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x)
        if (c > 0f) pos++ else if (c < 0f) neg++
    }
    return pos == 0 || neg == 0
}

/** Handle of the screen-space box [p] under [q]; corners win over sides, sides over the interior, the ring outside the corners rotates. */
fun hitHandle(p: List<Offset>, q: Offset, slop: Float, ring: Float): Int {
    for (i in 0..3) if ((p[i] - q).getDistance() <= slop) return i
    for (i in 0..3) if (distToSeg(q, p[i], p[(i + 1) % 4]) <= slop * 0.6f) return 4 + i
    if (inside(p, q)) return H_MOVE
    if (p.minOf { (it - q).getDistance() } <= ring) return H_ROT
    return H_NONE
}

/**
 * Document-space operation for dragging handle [h] of [box] from [d0] to [d].
 * [uniform] keeps the aspect ratio (every scale handle scales both axes); [freezeX] / [freezeY] forbid stretching an axis.
 */
fun gizmoOp(h: Int, box: List<Offset>, d0: Offset, d: Offset, uniform: Boolean, freezeX: Boolean = false, freezeY: Boolean = false): GOp {
    val m = Matrix()
    val ctr = (box[0] + box[2]) / 2f
    if (h == H_MOVE) { m.setTranslate(d.x - d0.x, d.y - d0.y); return GOp(m, 1f, 1f, 0f) }
    if (h == H_ROT) {
        var a = Math.toDegrees((atan2(d.y - ctr.y, d.x - ctr.x) - atan2(d0.y - ctr.y, d0.x - ctr.x)).toDouble()).toFloat()
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        m.setRotate(a, ctr.x, ctr.y); return GOp(m, 1f, 1f, a)
    }
    val ux = box[1] - box[0]; val vx = box[3] - box[0]
    val u = ux / max(ux.getDistance(), 1e-3f); val v = vx / max(vx.getDistance(), 1e-3f)
    val corner = h < 4
    val anchor = if (corner) box[(h + 2) % 4] else when (h) {
        4 -> (box[2] + box[3]) / 2f; 5 -> (box[0] + box[3]) / 2f; 6 -> (box[0] + box[1]) / 2f; else -> (box[1] + box[2]) / 2f
    }
    val r0 = d0 - anchor; val r1 = d - anchor
    val x0 = dot(r0, u); val y0 = dot(r0, v); val x1 = dot(r1, u); val y1 = dot(r1, v)
    fun ratio(n: Float, o: Float) = if (abs(o) < 1e-3f) 1f else (n / o).coerceIn(0.02f, 20f)
    var sx = 1f; var sy = 1f
    if (corner) {
        if (uniform) { val den = x0 * x0 + y0 * y0; val f = if (den < 1e-3f) 1f else ((x1 * x0 + y1 * y0) / den).coerceIn(0.02f, 20f); sx = f; sy = f }
        else { sx = ratio(x1, x0); sy = ratio(y1, y0) }
    } else if (h == 4 || h == 6) { sy = ratio(y1, y0); sx = if (uniform) sy else 1f }
    else { sx = ratio(x1, x0); sy = if (uniform) sx else 1f }
    if (freezeX) sx = 1f
    if (freezeY) sy = 1f
    val ang = Math.toDegrees(atan2(u.y, u.x).toDouble()).toFloat()
    m.setTranslate(-anchor.x, -anchor.y); m.postRotate(-ang); m.postScale(sx, sy); m.postRotate(ang); m.postTranslate(anchor.x, anchor.y)
    return GOp(m, sx, sy, 0f)
}

// ---------- boxes ----------

fun rectBox(r: RectF, m: Matrix? = null): List<Offset> {
    val a = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
    m?.mapPoints(a)
    return List(4) { Offset(a[it * 2], a[it * 2 + 1]) }
}

fun camBox(cam: Camera, cut: Cut, t: Int): List<Offset> {
    val s = cam.sc(t); val hw = cam.w * s / 2f; val hh = cam.h * s / 2f
    val c = Offset(cam.x(t, cut.w), cam.y(t, cut.h)); val r = cam.rot(t)
    return listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)).map { c + gRot(it, r) }
}

/** [content] (in the folder's local page space) carried through the folder's transform at frame t. */
fun folderBox(cut: Cut, f: Layer, t: Int, content: RectF): List<Offset> {
    val tr = f.transformAt(t); val c = Offset(cut.w / 2f, cut.h / 2f)
    return rectBox(content).map { gRot((it - c) * tr.scale, tr.rotation) + c + Offset(tr.x, tr.y) }
}

/** Tight bounds of the selected pixels. */
fun maskBounds(s: Sel): RectF? {
    var x0 = s.w; var y0 = s.h; var x1 = -1; var y1 = -1
    for (y in 0 until s.h) {
        val row = y * s.w; var hit = false
        for (x in 0 until s.w) if (s.mask[row + x]) { hit = true; if (x < x0) x0 = x; if (x > x1) x1 = x }
        if (hit) { if (y < y0) y0 = y; y1 = y }
    }
    return if (x1 < 0) null else RectF((s.ox + x0).toFloat(), (s.oy + y0).toFloat(), (s.ox + x1 + 1).toFloat(), (s.oy + y1 + 1).toFloat())
}

/** Bounds (sampled every 4 px) of one cel's visible pixels, or null when it is empty. Heavy: runs off the main thread, results are cached per cel. */
fun celContentBounds(strokes: androidx.compose.runtime.snapshots.SnapshotStateList<Ink>, w: Int, h: Int): RectF? {
    var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
    val row = IntArray(w)
    val bmp = CelCache.get(strokes, w, h).bmp
    var y = 0
    while (y < h) {
        bmp.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            if ((row[x] ushr 24) != 0) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
            x += 4
        }
        y += 4
    }
    return if (x1 < 0) null else RectF(max(0, x0 - 4).toFloat(), max(0, y0 - 4).toFloat(), min(w, x1 + 5).toFloat(), min(h, y1 + 5).toFloat())
}

// ---------- drawing (screen space) ----------

fun DrawScope.drawGizmo(p: List<Offset>, color: Color, tint: Boolean = false) {
    val path = Path().apply { moveTo(p[0].x, p[0].y); for (i in 1..3) lineTo(p[i].x, p[i].y); close() }
    if (tint) drawPath(path, color.copy(alpha = 0.10f))
    drawPath(path, Color.White, style = Stroke(3.5f, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(1.8f, join = StrokeJoin.Round))
    fun sq(c: Offset, s: Float) {
        drawRect(Color.White, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
        drawRect(color, Offset(c.x - s / 2, c.y - s / 2), Size(s, s), style = Stroke(2f))
    }
    for (i in 0..3) sq((p[i] + p[(i + 1) % 4]) / 2f, 12f)
    for (i in 0..3) sq(p[i], 18f)
    // Rotation grab points sit just outside each corner, making the rotate zone discoverable.
    val center = (p[0] + p[2]) / 2f
    for (i in 0..3) {
        val v = p[i] - center
        val len = v.getDistance().coerceAtLeast(1f)
        val q = p[i] + v / len * 48f
        drawCircle(Color.White, 7f, q)
        drawCircle(color, 5f, q)
    }
}
