package com.example.inbetween

import android.graphics.Path
import android.graphics.Rect
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/* Selection helpers (add / subtract, stroke-aware refinement, outline) and the smarter flood fill. See Tools.kt for the basic mask code. */

// ---------- selection operations ----------

private fun Sel.has(x: Int, y: Int): Boolean { val lx = x - ox; val ly = y - oy; return lx in 0 until w && ly in 0 until h && mask[ly * w + lx] }

/**
 * Combines [add] (a new selection) with the current one. [op]: 0 replace, 1 add to the selection, 2 erase from the selection.
 * Returns null when nothing is left (or when there was nothing to erase from).
 */
fun combineSel(old: Sel?, add: Sel, op: Int, cut: Cut): Sel? {
    if (op == 0 || (old == null && op == 1)) return add.takeIf { s -> s.mask.any { it } }
    if (old == null) return null
    val win = Rect(old.ox, old.oy, old.ox + old.w, old.oy + old.h)
    if (op == 1) {
        val u = Rect(win); u.union(add.ox, add.oy, add.ox + add.w, add.oy + add.h)
        if (u.width().toLong() * u.height().toLong() > WINDOW_PIXEL_CAP) {
            // too large for one mask: keep the old window and whatever of the new selection lies inside the Cut
            val cutR = Rect(0, 0, cut.w, cut.h)
            val a = Rect(add.ox, add.oy, add.ox + add.w, add.oy + add.h)
            if (a.intersect(cutR)) win.union(a)
            if (win.width().toLong() * win.height().toLong() > WINDOW_PIXEL_CAP) return add
        } else win.set(u)
    }
    val w = win.width(); val h = win.height()
    val out = BooleanArray(w * h)
    var any = false
    for (y in 0 until h) {
        val gy = win.top + y
        for (x in 0 until w) {
            val gx = win.left + x
            val inOld = old.has(gx, gy); val inNew = add.has(gx, gy)
            val v = if (op == 1) inOld || inNew else inOld && !inNew
            if (v) { out[y * w + x] = true; any = true }
        }
    }
    return if (any) Sel(win.left, win.top, w, h, out) else null
}

/** Runs of the whole selection (y, x0, x1 triples in document space). */
fun selRuns(s: Sel): IntArray = runsOf(s.mask, s.w, s.h, s.ox, s.oy)

/** Fills the selection with the current colour on the current drawing layer (one undo step). */
fun fillSelectionNow(ed: Editor, cut: Cut) {
    val s = ed.sel ?: return
    val l = cut.drawLayer?.takeIf { !it.locked && it.visible } ?: return
    val runs = selRuns(s)
    if (runs.isEmpty()) return
    val cel = cut.ensureCel(l); val ts = TileCache.get(cel.strokes)
    val ink = Ink(FloatArray(0), ed.color.toArgb() or (0xFF shl 24), Brush(""), 1, runs, null)
    ts.stamp(ink); cut.commit(cel, ink); ts.count = cel.strokes.size
}

// ---------- stroke-aware selection ----------

/**
 * Makes a rectangle / lasso selection follow the strokes of the drawing instead of the coarse outline that was drawn:
 * only painted pixels (plus their anti-aliased edge) stay selected, and a stroke that is mostly inside the outline and pokes a few pixels
 * out of it is selected as far as it pokes out ([snap] px). Strokes mostly outside are never pulled in, so the drawing outside the outline keeps
 * its place. Returns [raw] unchanged when the window is very large or nothing is painted inside.
 */
fun strokeAwareSel(raw: Sel, set: TileSet, snap: Int = 6): Sel {
    val w = raw.w; val h = raw.h; val n = w * h
    if (n > 3_000_000 || n <= 0) return raw
    val px = IntArray(n); set.readRect(raw.ox, raw.oy, w, h, px)
    val ink = BooleanArray(n) { (px[it] ushr 24) > 8 }
    val queue = IntArray(n) // scratch for the dilation first, then the component search
    val tmp = BooleanArray(n); val ink1 = BooleanArray(n); val near = BooleanArray(n)
    dilateSquare(ink, tmp, ink1, queue, w, h, 1)
    dilateSquare(raw.mask, tmp, near, queue, w, h, snap)
    // connected strokes (8 neighbours): how much of each lies inside the outline
    val label = IntArray(n) { -1 }
    var total = IntArray(64); var inside = IntArray(64); var nc = 0
    for (s in 0 until n) {
        if (!ink[s] || label[s] >= 0) continue
        if (nc == total.size) { total = total.copyOf(nc * 2); inside = inside.copyOf(nc * 2) }
        val id = nc++
        var head = 0; var tail = 0
        queue[tail++] = s; label[s] = id
        var t = 0; var inn = 0
        while (head < tail) {
            val i = queue[head++]; t++; if (raw.mask[i]) inn++
            val x = i % w; val y = i / w
            for (dy in -1..1) {
                val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; if (xx < 0 || xx >= w) continue
                    val j = yy * w + xx
                    if (ink[j] && label[j] < 0) { label[j] = id; queue[tail++] = j }
                }
            }
        }
        total[id] = t; inside[id] = inn
    }
    val out = BooleanArray(n)
    var any = false
    for (i in 0 until n) {
        val take = (raw.mask[i] && ink1[i]) || (ink[i] && near[i] && label[i] >= 0 && inside[label[i]] * 5 >= total[label[i]] * 3)
        if (take) { out[i] = true; any = true }
    }
    return if (any) Sel(raw.ox, raw.oy, w, h, out) else raw
}

// ---------- selection outline (marching ants) ----------

/**
 * Outline of the selected pixels as closed paths in document space (one corner per direction change, so a dash pattern flows along
 * the whole contour). Null when the selection is too complex to outline cheaply. Meant for a background thread.
 */
fun buildOutline(s: Sel, maxEdges: Int = 600_000): Path? {
    val w = s.w; val h = s.h; val m = s.mask
    val vw = w + 1
    if (vw.toLong() * (h + 1) > 12_000_000L) return null
    var from = IntArray(1024); var to = IntArray(1024); var cnt = 0
    fun edge(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (cnt == from.size) { from = from.copyOf(cnt * 2); to = to.copyOf(cnt * 2) }
        from[cnt] = y0 * vw + x0; to[cnt] = y1 * vw + x1; cnt++
        return cnt <= maxEdges
    }
    // interior stays on the right of every directed edge
    for (y in 0 until h) for (x in 0 until w) {
        if (!m[y * w + x]) continue
        if (y == 0 || !m[(y - 1) * w + x]) if (!edge(x, y, x + 1, y)) return null
        if (x == w - 1 || !m[y * w + x + 1]) if (!edge(x + 1, y, x + 1, y + 1)) return null
        if (y == h - 1 || !m[(y + 1) * w + x]) if (!edge(x + 1, y + 1, x, y + 1)) return null
        if (x == 0 || !m[y * w + x - 1]) if (!edge(x, y + 1, x, y)) return null
    }
    if (cnt == 0) return null
    val out1 = IntArray(vw * (h + 1)) { -1 }
    val out2 = HashMap<Int, Int>()
    for (e in 0 until cnt) { val st = from[e]; if (out1[st] < 0) out1[st] = e else out2[st] = e }
    val used = BooleanArray(cnt)
    val path = Path()
    fun dirOf(e: Int): Int { val a = from[e]; val b = to[e]; return when { b == a + 1 -> 0; b == a + vw -> 1; b == a - 1 -> 2; else -> 3 } }
    fun px(v: Int) = (s.ox + v % vw).toFloat()
    fun py(v: Int) = (s.oy + v / vw).toFloat()
    for (e0 in 0 until cnt) {
        if (used[e0]) continue
        path.moveTo(px(from[e0]), py(from[e0]))
        var e = e0; var lastDir = -1
        while (true) {
            used[e] = true
            val d = dirOf(e)
            if (lastDir >= 0 && d != lastDir) path.lineTo(px(from[e]), py(from[e]))
            lastDir = d
            val v = to[e]
            val c1 = out1[v]; val c2 = out2[v] ?: -1
            val nx = if (c1 >= 0 && !used[c1]) c1 else if (c2 >= 0 && !used[c2]) c2 else -1
            if (nx < 0) { path.lineTo(px(v), py(v)); break }
            e = nx
        }
        path.close()
    }
    return path
}

// ---------- smarter fill ----------

/** Scratch space of [smartFlood] (all w * h). */
class FillWork(n: Int) {
    val orig = BooleanArray(n); val region = BooleanArray(n); val tmp = BooleanArray(n); val aux = BooleanArray(n); val queue = IntArray(n)
}

/** Gap radii tried, smallest first, when the fill from the tapped point escapes through an imperfectly closed outline. */
private val AUTO_GAPS = intArrayOf(3, 6, 10, 16, 24)

private fun touchesBorder(m: BooleanArray, w: Int, h: Int): Boolean {
    for (x in 0 until w) if (m[x] || m[(h - 1) * w + x]) return true
    for (y in 0 until h) if (m[y * w] || m[y * w + w - 1]) return true
    return false
}

/**
 * Flood from the seed with gap closing of [gap] px, written to [out]; returns the gap radius that was used.
 * Differences to the plain [gapFillMask]: (1) the fill is grown back by the same radius inside the original region, so closing a gap no longer leaves a
 * band of unfilled pixels along the lines; (2) with [auto], a fill that reaches the edge of the work window (it leaked through a gap in the outline) is
 * repeated with growing gap radii until it stays inside; if none does, the plain result is kept (e.g. filling the outside of a drawing on purpose).
 */
fun smartFlood(lvl: ShortArray, w: Int, h: Int, sx: Int, sy: Int, tol: Int, gap: Int, auto: Boolean, out: BooleanArray, ws: FillWork): Int {
    val n = w * h
    maskFrom(lvl, tol, ws.orig)
    fun run(r: Int) {
        System.arraycopy(ws.orig, 0, ws.region, 0, n)
        gapFillMask(ws.region, out, ws.tmp, ws.queue, w, h, sx, sy, r)
        if (r > 0) {
            dilateSquare(out, ws.aux, ws.tmp, ws.queue, w, h, r)
            for (i in 0 until n) out[i] = ws.tmp[i] && ws.orig[i]
        }
    }
    run(gap)
    if (!auto || gap > 0 || !touchesBorder(out, w, h)) return gap
    for (g in AUTO_GAPS) {
        run(g)
        if (!touchesBorder(out, w, h)) return g
    }
    run(gap) // nothing contained it: keep the plain result
    return gap
}

/** Pixels of the 4-connected area around (cx, cy) whose colour stays within [level] of that pixel's colour, counted up to [limit]; [box] gets its bounds. */
private fun localArea(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, level: Int, limit: Int, mark: IntArray, stamp: Int, queue: IntArray, box: IntArray?): Int {
    val seed = px[cy * w + cx]
    val sa = seed ushr 24; val sr = (seed shr 16) and 255; val sg = (seed shr 8) and 255; val sb = seed and 255
    var head = 0; var tail = 0
    queue[tail++] = cy * w + cx; mark[cy * w + cx] = stamp
    var x0 = cx; var x1 = cx; var y0 = cy; var y1 = cy
    while (head < tail && tail < limit) {
        val i = queue[head++]; val x = i % w; val y = i / w
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        for (k in 0 until 4) {
            val j = when (k) { 0 -> if (x > 0) i - 1 else -1; 1 -> if (x < w - 1) i + 1 else -1; 2 -> if (y > 0) i - w else -1; else -> if (y < h - 1) i + w else -1 }
            if (j < 0 || mark[j] == stamp) continue
            val c = px[j]
            val d = max(max(abs((c ushr 24) - sa), abs(((c shr 16) and 255) - sr)), max(abs(((c shr 8) and 255) - sg), abs((c and 255) - sb)))
            if (d > level) continue
            mark[j] = stamp; queue[tail++] = j
        }
    }
    if (box != null) { box[0] = x0; box[1] = y0; box[2] = x1; box[3] = y1 }
    return tail
}

/**
 * Tapping on a line (or just beside it) used to fill the line itself. When the tapped pixel belongs to a thin shape, the seed moves to the nearest
 * open area (up to 14 px away) and the fill continues from there. Returns the (possibly moved) seed as x, y in window pixels.
 */
fun snapSeed(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, level: Int): IntArray {
    val n = w * h
    val mark = IntArray(n); val queue = IntArray(6016)
    val box = IntArray(4)
    val own = localArea(px, w, h, sx, sy, level, 6000, mark, 1, queue, box)
    val thin = own < 6000 && min(box[2] - box[0], box[3] - box[1]) + 1 <= 10
    if (!thin) return intArrayOf(sx, sy)
    var best = own; var bx = sx; var by = sy
    var stamp = 2
    val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1))
    for (r in intArrayOf(3, 6, 10, 14)) for (d in dirs) {
        val cx = sx + d[0] * r; val cy = sy + d[1] * r
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue
        val a = localArea(px, w, h, cx, cy, level, 3000, mark, stamp++, queue, null)
        if (a > best && a >= 1500) { best = a; bx = cx; by = cy }
    }
    return intArrayOf(bx, by)
}
