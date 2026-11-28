package com.example.inbetween

import android.graphics.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.*
import java.io.ByteArrayOutputStream

/** A pixel selection in document space. The mask covers the window (ox, oy, w, h); with Bleed off the window is the Cut. */
class Sel(val ox: Int, val oy: Int, val w: Int, val h: Int, val mask: BooleanArray) {
    constructor(w: Int, h: Int, mask: BooleanArray) : this(0, 0, w, h, mask)
    /** Outline of the selection for the shimmering border; built once on a background thread (see SelectionFill.kt). state: 0 not started, 1 building, 2 done (outline may stay null when too complex). */
    @Volatile var outline: Path? = null
    @Volatile var outlineState = 0
    val overlay: Bitmap by lazy {
        val px = IntArray(w * h); for (i in px.indices) if (mask[i]) px[i] = 0x553B82F6
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    /** Inverse inside the Cut (Bleed off) or inside Cut + selection + painted content (Bleed on, capped to a mask-sized window). */
    fun inverted(cut: Cut, bleed: Boolean, set: TileSet?): Sel {
        val win = Rect(0, 0, cut.w, cut.h)
        if (bleed) {
            win.union(ox, oy, ox + w, oy + h)
            set?.tileBounds()?.let { win.union(it) }
            if (win.width().toLong() * win.height().toLong() > WINDOW_PIXEL_CAP) {
                win.set(0, 0, cut.w, cut.h); win.union(ox, oy, ox + w, oy + h)
                if (win.width().toLong() * win.height().toLong() > WINDOW_PIXEL_CAP) win.set(ox, oy, ox + w, oy + h)
            }
        }
        val nw = win.width(); val nh = win.height()
        val out = BooleanArray(nw * nh)
        for (y in 0 until nh) {
            val gy = win.top + y - oy
            for (x in 0 until nw) {
                val gx = win.left + x - ox
                val inside = gx in 0 until w && gy in 0 until h
                out[y * nw + x] = !(inside && mask[gy * w + gx])
            }
        }
        return Sel(win.left, win.top, nw, nh, out)
    }
}

fun invertSelection(ed: Editor, cut: Cut) {
    val s = ed.sel ?: return
    val set = cut.drawLayer?.celAt(cut.time)?.strokes?.let { TileCache.get(it) }
    ed.sel = s.inverted(cut, ed.drawOutsideFrame, set)
}

data class Ell(val c: Offset, val rx: Float, val ry: Float, val a: Float)

/** Line tool, Bezier curve: end points a and b, handles c1 / c2; [stage] 1 = the first handle follows the pen, 2 = the second one.
 *  While stage is 1, c2 rests on b (no second handle shown, nothing warping the curve); it only appears and follows the pen from stage 2.
 *  [one] = the "one curve handle" option: c1 is the single control point of a quadratic curve (both ends are pulled toward it), c2 is unused and the tap that places c1 applies the curve. */
data class Bez(val a: Offset, val b: Offset, val c1: Offset, val c2: Offset, val stage: Int, val one: Boolean = false) {
    /** The curve as points: a cubic through c1 / c2, or in [one] mode the quadratic with control point c1 (as the equivalent cubic). */
    fun points(): List<Offset> =
        if (one) bezierPts(a, b, a + (c1 - a) * (2f / 3f), b + (c1 - b) * (2f / 3f)) else bezierPts(a, b, c1, c2)
}

fun bezSet(z: Bez, d: Offset): Bez = if (z.stage == 1) z.copy(c1 = d) else z.copy(c2 = d)

/** The cubic Bezier a, c1, c2, b sampled about every 3 document px (at least 24 steps), both end points included. */
fun bezierPts(a: Offset, b: Offset, c1: Offset, c2: Offset): List<Offset> {
    val hull = (c1 - a).getDistance() + (c2 - c1).getDistance() + (b - c2).getDistance()
    val n = max(24, (hull / 3f).toInt()).coerceAtMost(600)
    val out = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val u = i.toFloat() / n; val v = 1f - u
        val w0 = v * v * v; val w1 = 3f * v * v * u; val w2 = 3f * v * u * u; val w3 = u * u * u
        out.add(Offset(a.x * w0 + c1.x * w1 + c2.x * w2 + b.x * w3, a.y * w0 + c1.y * w1 + c2.y * w2 + b.y * w3))
    }
    return out
}

class PenState {
    var kind = 0; var tl = 0
    var start = Offset.Zero; var last = Offset.Zero; var moved = false; var sx0 = 0f
    var downScr = Offset.Zero; var farScr = false; var refOutside = false // reference layers: pen-down point in screen px, moved beyond the tap slop, down point outside every image
    var px: IntArray? = null; var sx = 0; var sy = 0; var gap0 = 0; var gapNow = 0; var lastT = 0L
    var a0 = 0f; var base = 0f; var drag = 0; var vi = -1
    var guideOrigin0 = Offset.Zero; var guidePivot0 = Offset.Zero; var guideDown0 = Offset.Zero; var guideRotation0 = 0f; var guideDownAngle = 0f
    var guideVpPositions0: List<Pair<Int, Offset>> = emptyList()
    var lvl: ShortArray? = null; var baseMask: BooleanArray? = null; var mask: BooleanArray? = null; var gapTmp: BooleanArray? = null; var buf: IntArray? = null; var bmp: Bitmap? = null
    var grow = 0; var g0 = 0; var y0 = 0f; var psum = 0f; var pn = 0; var snap: Snap? = null
    var wx = 0; var wy = 0; var ww = 0; var wh = 0 // fill / wand window (document space)
    var selOp = 0                                  // selection being drawn: 0 replace, 1 add, 2 erase
    var work: FillWork? = null; var autoGap = -1; var gapEdited = false // smarter fill: scratch space, the gap chosen automatically (-1 = not yet), dragged by hand
    var wpNode = -1; var wpPin: Pin? = null; var wpNew = false; var wpStart: WarpMesh? = null; var wpD0 = Offset.Zero; var wpQ0 = Offset.Zero // warp / puppet drag
}

/** Mask -> runs (y, x0, x1) triples. */
fun runsOf(mask: BooleanArray, w: Int, h: Int, ox: Int = 0, oy: Int = 0): IntArray {
    var buf = IntArray(768); var n = 0
    for (y in 0 until h) {
        var x = 0
        while (x < w) {
            if (!mask[y * w + x]) { x++; continue }
            val x0 = x
            while (x < w && mask[y * w + x]) x++
            if (n + 3 > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n] = y + oy; buf[n + 1] = x0 + ox; buf[n + 2] = x + ox; n += 3
        }
    }
    return buf.copyOf(n)
}

fun androidPath(pts: List<Offset>): Path {
    val p = Path(); p.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
    p.close(); return p
}

/** Bleed off: the mask covers the Cut. Bleed on: it covers the path's own bounds, anywhere in the document (capped to a mask-sized window). */
fun selFromPath(path: Path, cut: Cut, bleed: Boolean): Sel {
    val win = Rect(0, 0, cut.w, cut.h)
    if (bleed) {
        val b = RectF(); path.computeBounds(b, true)
        win.set(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        if (win.width() < 1) win.right = win.left + 1
        if (win.height() < 1) win.bottom = win.top + 1
        if (win.width().toLong() * win.height().toLong() > WINDOW_PIXEL_CAP) { val cx = win.centerX(); val cy = win.centerY(); win.set(cx - 1600, cy - 1200, cx + 1600, cy + 1200) }
    }
    val w = win.width(); val h = win.height()
    val local = Path(path); local.offset(-win.left.toFloat(), -win.top.toFloat())
    val rg = Region(); rg.setPath(local, Region(0, 0, w, h))
    val mask = BooleanArray(w * h); val it = RegionIterator(rg); val r = Rect()
    while (it.next(r)) for (y in r.top until r.bottom) java.util.Arrays.fill(mask, y * w + r.left, y * w + r.right, true)
    return Sel(win.left, win.top, w, h, mask)
}

/** The masked pixels cut out of a bitmap (and cleared from it). */
class Lift(val bmp: Bitmap, val left: Int, val top: Int)

fun lift(bmp: Bitmap, runs: IntArray): Lift? {
    if (runs.isEmpty()) return null
    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = 0; var b = 0
    for (i in runs.indices step 3) { t = min(t, runs[i]); b = max(b, runs[i] + 1); l = min(l, runs[i + 1]); r = max(r, runs[i + 2]) }
    val tmp = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
    var buf = IntArray(0)
    for (i in runs.indices step 3) {
        val y = runs[i]; val x0 = runs[i + 1]; val len = runs[i + 2] - x0
        if (buf.size < len) buf = IntArray(len)
        bmp.getPixels(buf, 0, len, x0, y, len, 1); tmp.setPixels(buf, 0, len, x0 - l, y - t, len, 1)
        java.util.Arrays.fill(buf, 0, len, 0); bmp.setPixels(buf, 0, len, x0, y, len, 1)
    }
    return Lift(tmp, l, t)
}

fun drawLift(cv: Canvas, lf: Lift, m: FloatArray) {
    val mt = Matrix(); mt.setValues(m); mt.preTranslate(lf.left.toFloat(), lf.top.toFloat())
    cv.drawBitmap(lf.bmp, mt, Paint(Paint.FILTER_BITMAP_FLAG))
}

/** One cel participating in a live multi-frame transform. */
class XformItem(val cel: Cel, val entry: TileSet, val runs: IntArray, val lift: Lift)

/** Live transform shared by every selected drawing cel; matrix is in document space. */
class Xform(val items: List<XformItem>) {
    // Keep the legacy primary accessors: the first item is the playhead cel and owns the gizmo bounds.
    val primary: XformItem get() = items.first()
    val cel: Cel get() = primary.cel
    val entry: TileSet get() = primary.entry
    val runs: IntArray get() = primary.runs
    val lift: Lift get() = primary.lift
    fun itemFor(strokes: Any): XformItem? = items.firstOrNull { it.cel.strokes === strokes }
    val mat = Matrix()
    var rev by mutableIntStateOf(0)

    // ----- Warp / Puppet Warp -----
    /** 0 = move / scale / rotate handles, 1 = grid warp, 2 = Puppet Warp. */
    var mode by mutableIntStateOf(0)
    /** Deformation of the lifted pixels in document space BEFORE [mat]; null = not deformed. */
    var mesh: WarpMesh? = null
    /** Grid warp: number of cells per side, and how far a nudge of one node carries its neighbours (0..1). */
    var warpN by mutableIntStateOf(4)
    var strength by mutableFloatStateOf(0.5f)
    val pins = ArrayList<Pin>()
    /** Puppet Warp: the pin last placed or touched (-1 = none), and the radius new pins get (0 = no limit). */
    var pinSel by mutableIntStateOf(-1)
    var pinRadius by mutableFloatStateOf(0f)
    /** Puppet Warp: the mesh the pins deform (the mesh as it was when the tool was entered). */
    var puppetBase: WarpMesh? = null

    private fun liftRect() = RectF(lift.left.toFloat(), lift.top.toFloat(), (lift.left + lift.bmp.width).toFloat(), (lift.top + lift.bmp.height).toFloat())

    /** The mesh a deformation tool starts from: the current one, or a plain grid over the lifted pixels. */
    fun meshOrGrid(cells: Int): WarpMesh = mesh ?: liftRect().let { WarpMesh.grid(it.left, it.top, it.width(), it.height(), cells, cells) }

    fun switchMode(m: Int) {
        if (m == mode) return
        mode = m; pins.clear(); pinSel = -1; puppetBase = null
        when (m) {
            1 -> { val cur = mesh; if (cur == null) mesh = meshOrGrid(warpN) else warpN = cur.cols.coerceIn(2, 16) }
            2 -> { val base = meshOrGrid(PUPPET_CELLS).resampled(PUPPET_CELLS, PUPPET_CELLS); puppetBase = base; mesh = base.copy() }
        }
        rev++
    }

    /** Grid warp: changes the number of cells (the deformation so far is kept). */
    fun setIncrements(n: Int) {
        warpN = n.coerceIn(2, 16)
        mesh = (mesh ?: meshOrGrid(warpN)).resampled(warpN, warpN)
        rev++
    }

    /** Puppet Warp: recomputes the mesh from the pins. */
    fun applyPins() { puppetBase?.let { mesh = puppetMesh(it, pins) }; rev++ }

    fun clearPins() { pins.clear(); pinSel = -1; applyPins() }

    /** Puppet Warp: sets how far the selected pin reaches (document px, 0 = no limit); it is also the radius the next pins get. */
    fun applyPinRadius(r: Float) { pinRadius = r; pins.getOrNull(pinSel)?.r = r; applyPins() }

    /** Back to the untouched selection: no transform, no deformation, no pins. */
    fun resetAll() {
        mat.reset(); pins.clear(); pinSel = -1; puppetBase = null
        mesh = when (mode) {
            1 -> meshOrGridNew(warpN)
            2 -> meshOrGridNew(PUPPET_CELLS).also { puppetBase = it.copy() }
            else -> null
        }
        rev++
    }

    private fun meshOrGridNew(cells: Int): WarpMesh = liftRect().let { WarpMesh.grid(it.left, it.top, it.width(), it.height(), cells, cells) }

    /** Corners of the lifted pixels or of the deformed mesh, in document space before [mat]. */
    fun contentBounds(): RectF = mesh?.bounds() ?: liftRect()
}

const val PUPPET_CELLS = 24

fun beginTransform(ed: Editor, cut: Cut) {
    val s = ed.sel ?: return
    val activeLayer = cut.drawLayer ?: return
    if (activeLayer.locked) return
    val activeCel = activeLayer.celAt(cut.time) ?: return
    val selected = if (ed.selCels.isNotEmpty() && activeCel in ed.selCels) ed.selCels.toList() else listOf(activeCel)
    val owner = java.util.IdentityHashMap<Cel, Layer>()
    cut.layers.filter { it.kind == 0 && !it.locked }.forEach { layer -> layer.cels.forEach { owner[it] = layer } }
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    val items = ArrayList<XformItem>()
    // Keep the playhead drawing first so the on-canvas handles remain anchored to the visible cel.
    val ordered = listOf(activeCel) + selected.filter { it !== activeCel }
    for (cel in ordered) {
        if (owner[cel] == null || cel.strokes.isEmpty() || !seen.add(cel.strokes)) continue
        val ts = TileCache.get(cel.strokes)
        val runs = runsOf(s.mask, s.w, s.h, s.ox, s.oy)
        val lf = ts.liftRuns(runs) ?: continue
        ts.pinned = true // lifted pixels are shown by the live overlay until the transform is committed/cancelled
        items.add(XformItem(cel, ts, runs, lf))
    }
    if (items.isEmpty()) return
    ed.xform = Xform(items)
}

fun cancelTransform(ed: Editor) {
    ed.xform?.items?.forEach { it.entry.count = Int.MAX_VALUE; it.entry.pinned = false }
    ed.xform = null
}

fun applyTransform(ed: Editor, cut: Cut) {
    val x = ed.xform ?: return
    val v = FloatArray(9); x.mat.getValues(v)
    val baked = x.mesh?.mapped(x.mat) // Warp / Puppet Warp: the matrix is part of the mesh
    for (item in x.items) {
        if (baked != null) {
            item.entry.drawMeshTiles(item.lift, baked)
            cut.commit(item.cel, Ink(FloatArray(0), 0, Brush(""), 4, item.runs, baked.data()))
        } else {
            item.entry.drawLiftTiles(item.lift, v)
            cut.commit(item.cel, Ink(FloatArray(0), 0, Brush(""), 2, item.runs, v.copyOf()))
        }
        item.entry.count = item.cel.strokes.size
        item.entry.pinned = false
    }
    ed.xform = null; ed.sel = null
}

// ---------- shapes ----------
private fun seg(a: Offset, b: Offset, out: MutableList<Offset>) {
    val n = max(1, (hypot(b.x - a.x, b.y - a.y) / 3f).toInt())
    for (i in 0..n) out.add(Offset(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n))
}

fun ellPts(c: Offset, rx: Float, ry: Float, ang: Float): List<Offset> {
    val n = max(24, (2 * PI * max(rx, ry) / 3f).toInt()); val ca = cos(ang); val sa = sin(ang)
    return List(n + 1) { i ->
        val t = 2f * PI.toFloat() * i / n; val x = rx * cos(t); val y = ry * sin(t)
        Offset(c.x + x * ca - y * sa, c.y + x * sa + y * ca)
    }
}

/** 0 line, 1 rectangle, 2 circle (a = center, b = point on the rim). */
fun shapePts(shape: Int, a: Offset, b: Offset): List<Offset> = when (shape) {
    1 -> ArrayList<Offset>().also { o -> val c = listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y), a); for (i in 0 until 4) seg(c[i], c[i + 1], o) }
    2 -> { // circle inscribed in the square dragged from corner a to b (drawn from its exterior)
        val dx = b.x - a.x; val dy = b.y - a.y; val dia = max(abs(dx), abs(dy))
        val c = Offset(a.x + (if (dx < 0) -dia else dia) / 2f, a.y + (if (dy < 0) -dia else dia) / 2f)
        ellPts(c, dia / 2f, dia / 2f, 0f)
    }
    else -> ArrayList<Offset>().also { seg(a, b, it) }
}

// ---------- flood / wand / fill ----------

/** For each pixel, the lowest tolerance (0..255, channel difference to the seed color) at which it joins the seed's region;
 *  -1 = unreachable. One pass here, then any threshold is a cheap O(N) comparison. */
fun floodMap(px: IntArray, w: Int, h: Int, sx: Int, sy: Int): ShortArray {
    val n = w * h
    val lvl = ShortArray(n) { -1 }
    val seed = px[sy * w + sx]
    val sa = seed ushr 24; val sr = (seed shr 16) and 255; val sg = (seed shr 8) and 255; val sb = seed and 255
    val head = IntArray(256) { -1 }; val next = IntArray(n)
    val s0 = sy * w + sx
    lvl[s0] = 0; next[s0] = -1; head[0] = s0
    var cur = 0
    while (cur < 256) {
        val i = head[cur]
        if (i == -1) { cur++; continue }
        head[cur] = next[i]
        val x = i % w; val y = i / w
        for (k in 0 until 4) {
            val j = when (k) { 0 -> if (x > 0) i - 1 else -1; 1 -> if (x < w - 1) i + 1 else -1; 2 -> if (y > 0) i - w else -1; else -> if (y < h - 1) i + w else -1 }
            if (j < 0 || lvl[j] >= 0) continue
            val c = px[j]
            val d = max(max(abs((c ushr 24) - sa), abs(((c shr 16) and 255) - sr)), max(abs(((c shr 8) and 255) - sg), abs((c and 255) - sb)))
            val l = max(cur, d)
            lvl[j] = l.toShort(); next[j] = head[l]; head[l] = j
        }
    }
    return lvl
}

/** Threshold % -> channel tolerance. Square-root curve so low values already reach well beyond the seed color. */
fun tolToLevel(tol: Int) = (255f * sqrt(tol / 100f)).toInt()

/** Colour tolerance (%) of Magic Wand and Fill. It is fixed: the horizontal drag only adjusts the gap fill. */
const val WAND_FILL_TOL = 24
/** Finger-scrub distance (screen px) past the last frame needed to arm a new frame (about 3.5 frame steps of 48 px). */
const val NEW_FRAME_PULL = 168f
/** Two fingers closer than this (dp) arm the canvas-view reset on release. History: 48 (too hard) -> 80 -> 160 (armed during ordinary pinches) -> 112. */
const val RESET_PINCH_DP = 112f
/** A pen / finger tap on a reference layer may wander this many screen pixels (S Pen jitter) and still count as a tap. */
const val TAP_SLOP_PX = 40f
/** Tap slop (screen px) of a guide-joint / Displace-joint gesture: a tap on the selected joint deletes it, a movement beyond this drags it (finer than [TAP_SLOP_PX] so a joint can be nudged a little). */
const val JOINT_TAP_SLOP_PX = 12f
/** Largest gap-closing radius (px) reachable with the horizontal drag. */
const val MAX_GAP_FILL = 60

fun maskFrom(lvl: ShortArray, t: Int, out: BooleanArray) { for (i in lvl.indices) { val v = lvl[i].toInt(); out[i] = v in 0..t } }


/**
 * Square dilation of [src] by [r] pixels in O(w*h), independent of [r] (two separable passes).
 * [dst] (w*h booleans) and [scratch] (w*h ints) are scratch space; the result goes to [out], which may alias [src].
 */
fun dilateSquare(src: BooleanArray, dst: BooleanArray, out: BooleanArray, scratch: IntArray, w: Int, h: Int, r: Int) {
    val far = 1 shl 28
    for (y in 0 until h) { // horizontal: distance to the nearest true pixel in the row
        val row = y * w
        var last = -far
        for (x in 0 until w) { if (src[row + x]) last = x; dst[row + x] = x - last <= r }
        last = far
        for (x in w - 1 downTo 0) { if (src[row + x]) last = x; if (last - x <= r) dst[row + x] = true }
    }
    val nxt = IntArray(w) { far }
    for (y in h - 1 downTo 0) { // vertical: nearest true row below (kept in scratch) ...
        val row = y * w
        for (x in 0 until w) { if (dst[row + x]) nxt[x] = y; scratch[row + x] = nxt[x] }
    }
    val lst = IntArray(w) { -far }
    for (y in 0 until h) { // ... and above
        val row = y * w
        for (x in 0 until w) { if (dst[row + x]) lst[x] = y; out[row + x] = y - lst[x] <= r || scratch[row + x] - y <= r }
    }
}

/**
 * Expand the threshold-excluded pixels as barriers by [radius] px, then flood the remaining region from the seed.
 * This closes narrow line-art gaps before selection/fill, rather than merely expanding the finished mask.
 * [queue] (w*h ints) is scratch space. The cost does not depend on [radius].
 */
fun gapFillMask(region: BooleanArray, dst: BooleanArray, tmp: BooleanArray, queue: IntArray,
                w: Int, h: Int, sx: Int, sy: Int, radius: Int) {
    if (radius <= 0) { System.arraycopy(region, 0, dst, 0, region.size); return }
    for (i in region.indices) tmp[i] = !region[i]
    dilateSquare(tmp, dst, tmp, queue, w, h, radius) // tmp now holds the expanded barrier
    for (i in region.indices) region[i] = region[i] && !tmp[i]
    val seed = sy * w + sx
    region[seed] = true
    dst.fill(false)
    var head = 0; var tail = 0
    dst[seed] = true; queue[tail++] = seed
    while (head < tail) {
        val i = queue[head++]; val x = i % w; val y = i / w
        val left = if (x > 0) i - 1 else -1
        val right = if (x + 1 < w) i + 1 else -1
        val up = if (y > 0) i - w else -1
        val down = if (y + 1 < h) i + w else -1
        if (left >= 0 && region[left] && !dst[left]) { dst[left] = true; queue[tail++] = left }
        if (right >= 0 && region[right] && !dst[right]) { dst[right] = true; queue[tail++] = right }
        if (up >= 0 && region[up] && !dst[up]) { dst[up] = true; queue[tail++] = up }
        if (down >= 0 && region[down] && !dst[down]) { dst[down] = true; queue[tail++] = down }
    }
}

/** Expand (k > 0) or reduce (k < 0) a mask by k pixels. */
fun morph(src: BooleanArray, dst: BooleanArray, w: Int, h: Int, k: Int) {
    System.arraycopy(src, 0, dst, 0, src.size)
    if (k == 0) return
    val grow = k > 0
    var a = dst; var b = BooleanArray(src.size)
    repeat(abs(k)) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                b[i] = if (grow) a[i] || (x > 0 && a[i - 1]) || (x < w - 1 && a[i + 1]) || (y > 0 && a[i - w]) || (y < h - 1 && a[i + w])
                else a[i] && (x == 0 || a[i - 1]) && (x == w - 1 || a[i + 1]) && (y == 0 || a[i - w]) && (y == h - 1 || a[i + w])
            }
        }
        val t = a; a = b; b = t
    }
    if (a !== dst) System.arraycopy(a, 0, dst, 0, dst.size)
}

// ---------- images, import, flatten ----------

fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")

/** An image placed (fit, centered) on the canvas as a replayable operation. */
fun imageInk(cut: Cut, bytes: ByteArray): Ink? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    val s = min(cut.w / o.outWidth.toFloat(), cut.h / o.outHeight.toFloat())
    val m = Matrix(); m.postScale(s, s); m.postTranslate((cut.w - o.outWidth * s) / 2f, (cut.h - o.outHeight * s) / 2f)
    val v = FloatArray(9); m.getValues(v)
    return Ink(FloatArray(0), 0, Brush(""), 3, null, v, bytes)
}

/** Empty frames of the layer take the images in order; the rest become new one-frame cels at the end. */
fun fillLayer(l: Layer, images: List<Ink>) {
    val empties = l.cels.filter { it.strokes.isEmpty() }.toMutableList()
    for (ink in images) { val c = if (empties.isNotEmpty()) empties.removeAt(0) else Cel(1).also { l.cels.add(it) }; c.strokes.add(ink) }
}

fun layerFor(cut: Cut, group: String?, name: String): Layer {
    val gl = group?.let { gn -> cut.layers.firstOrNull { it.isGroup && safeName(it.name) == gn } ?: Layer(cut.nextLayer, gn, true).also { cut.nextLayer++; cut.layers.add(it) } }
    cut.layers.firstOrNull { !it.isGroup && it.kind == 0 && safeName(it.name) == name && (if (gl != null) it.group == gl.id else it.group < 0) }?.let { return it }
    val l = Layer(cut.nextLayer, name); cut.nextLayer++
    if (gl != null) { l.group = gl.id; cut.layers.add(cut.layers.indexOf(gl), l) } else cut.layers.add(l)
    return l
}

/** Zip laid out like the image-sequence export: Cut/Group/Layer_001.png (group folder optional). */
fun importZip(cut: Cut, entries: List<Pair<String, ByteArray>>) {
    val map = LinkedHashMap<Pair<String?, String>, MutableList<Pair<Int, ByteArray>>>()
    for ((path, bytes) in entries) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val file = parts.lastOrNull() ?: continue
        if (!Regex(".*\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE).matches(file)) continue
        val base = file.substringBeforeLast('.')
        val m = Regex("^(.*)_(\\d+)$").find(base)
        val lname = m?.groupValues?.get(1) ?: base; val num = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val g = if (parts.size >= 3) parts[parts.size - 2] else null
        map.getOrPut(g to lname) { ArrayList() }.add(num to bytes)
    }
    for ((key, list) in map) {
        list.sortBy { it.first }
        fillLayer(layerFor(cut, key.first, key.second), list.mapNotNull { imageInk(cut, it.second) })
    }
}

/** Flattens `up` into the drawing layer below it. The lower layer keeps its frame timing; each of its frames receives the
 *  upper drawing that overlaps it. */
fun flattenDown(cut: Cut, up: Layer) = cut.record { flattenDownImpl(cut, up) }

private fun flattenDownImpl(cut: Cut, up: Layer) {
    val low = cut.layers.subList(0, cut.layers.indexOf(up)).lastOrNull { it.kind == 0 } ?: return
    val ident = FloatArray(9).also { Matrix().getValues(it) }
    val cache = HashMap<Cel, Ink>()
    val extra = up.total() - low.total()
    if (extra > 0) low.cels.add(Cel(extra)) // the upper drawing outlasts the lower layer: add the missing frames
    var s = 0
    for (c in low.cels) {
        val e = s + c.dur
        val u = up.celAt(s)?.takeIf { it.strokes.isNotEmpty() }
            ?: up.cels.withIndex().firstOrNull { (j, uc) -> uc.strokes.isNotEmpty() && up.startOf(j) < e && up.startOf(j) + uc.dur > s }?.value
        if (u != null) {
            val ink = cache.getOrPut(u) {
                val src = CelCache.get(u.strokes, cut.w, cut.h)
                val out = Bitmap.createBitmap(cut.w, cut.h, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(src.bmp, 0f, 0f, Paint().apply { alpha = (up.opacity * 255).toInt() })
                val bos = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.PNG, 100, bos); out.recycle()
                Ink(FloatArray(0), 0, Brush(""), 3, null, ident, bos.toByteArray())
            }
            c.strokes.add(ink)
        }
        s = e
    }
    cut.layers.remove(up); if (cut.layerId == up.id) cut.layerId = low.id
}


// ---------- Bleed clean-up: delete ink outside the Cut ----------

/** Liang-Barsky: the [t0, t1] part of the segment inside the rectangle, or null when it misses it. */
private fun clipSegment(ax: Float, ay: Float, bx: Float, by: Float, rx0: Float, ry0: Float, rx1: Float, ry1: Float): FloatArray? {
    var t0 = 0f; var t1 = 1f
    val dx = bx - ax; val dy = by - ay
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(ax - rx0, rx1 - ax, ay - ry0, ry1 - ay)
    for (i in 0..3) {
        if (p[i] == 0f) { if (q[i] < 0f) return null }
        else {
            val r = q[i] / p[i]
            if (p[i] < 0f) { if (r > t1) return null; if (r > t0) t0 = r }
            else { if (r < t0) return null; if (r < t1) t1 = r }
        }
    }
    return floatArrayOf(t0, t1)
}

/**
 * Trims one ink back to the Cut. Returns null when nothing changes, otherwise the replacement inks (possibly none).
 * Brush / eraser strokes keep every point whose dab can still touch the Cut (the rectangle grown by the brush radius), so the
 * part inside the Cut looks the same; segments that cross that boundary are cut there. A stroke that leaves and re-enters becomes
 * several strokes. Fills are clipped to the Cut. Selection moves (kind 2) and imported images (kind 3) are left alone: removing
 * a move would put the lifted pixels back.
 */
private fun trimInkToCut(k: Ink, w: Float, h: Float): List<Ink>? {
    if (k.kind == 1) {
        val r = k.runs ?: return null
        val out = IntArray(r.size); var n = 0; var i = 0; var changed = false
        val wi = w.toInt(); val hi = h.toInt()
        while (i + 2 < r.size) {
            val y = r[i]; val x0 = max(0, r[i + 1]); val x1 = min(wi, r[i + 2])
            if (y in 0 until hi && x1 > x0) { out[n] = y; out[n + 1] = x0; out[n + 2] = x1; n += 3; if (x0 != r[i + 1] || x1 != r[i + 2]) changed = true }
            else changed = true
            i += 3
        }
        if (!changed) return null
        return if (n == 0) emptyList() else listOf(Ink(FloatArray(0), k.color, k.b, 1, out.copyOf(n), k.m, k.data))
    }
    if (k.kind != 0 || k.p.size < 4) return null
    val n = k.p.size / 4
    var widest = 1f
    for (i in 0 until n) widest = max(widest, k.p[i * 4 + 2])
    val m = k.b.size * 0.5f * widest + 1f
    val rx0 = -m; val ry0 = -m; val rx1 = w + m; val ry1 = h + m
    fun inside(i: Int) = k.p[i * 4] in rx0..rx1 && k.p[i * 4 + 1] in ry0..ry1
    if ((0 until n).all { inside(it) }) return null

    val out = ArrayList<Ink>()
    var cur = ArrayList<Float>()
    fun add(i: Int) { for (c in 0..3) cur.add(k.p[i * 4 + c]) }
    fun addAt(a: Int, b: Int, t: Float) { for (c in 0..3) cur.add(k.p[a * 4 + c] + (k.p[b * 4 + c] - k.p[a * 4 + c]) * t) }
    fun flush() { if (cur.isNotEmpty()) { out.add(Ink(cur.toFloatArray(), k.color, k.b, 0, k.runs, null, null, k.seed)); cur = ArrayList() } }

    if (inside(0)) add(0)
    for (i in 1 until n) {
        val a = i - 1
        val c = clipSegment(k.p[a * 4], k.p[a * 4 + 1], k.p[i * 4], k.p[i * 4 + 1], rx0, ry0, rx1, ry1)
        if (c == null) { flush(); continue }
        if (c[0] > 0f) { flush(); addAt(a, i, c[0]) } // the segment starts outside: a new piece begins where it enters
        if (c[1] < 1f) { addAt(a, i, c[1]); flush() } else add(i)
    }
    flush()
    return out
}

/**
 * Deletes ink outside the Cut. scope: 0 = current frame of the current layer, 1 = every frame of the current layer, 2 = every drawing /
 * background layer. Locked layers are skipped. Touched cels are replaced by trimmed copies inside one undo step. Returns the number of strokes changed.
 */
fun clearBleedInk(ed: Editor, cut: Cut, scope: Int): Int {
    val w = cut.w.toFloat(); val h = cut.h.toFloat()
    val targets: List<Layer> = if (scope == 2) cut.layers.filter { !it.isGroup && (it.kind == 0 || it.kind == 4) } else listOfNotNull(cut.drawLayer)
    class Plan(val layer: Layer, val index: Int, val cel: Cel)
    val plans = ArrayList<Plan>()
    var changed = 0
    for (l in targets) {
        if (l.locked) continue
        val indices = if (scope == 0) listOf(l.indexAt(cut.time)).filter { it >= 0 } else l.cels.indices.toList()
        for (i in indices) {
            val cel = l.cels[i]
            val kept = ArrayList<Ink>(cel.strokes.size)
            var any = false
            for (k in cel.strokes) {
                val r = trimInkToCut(k, w, h)
                if (r == null) kept.add(k) else { any = true; changed++; kept.addAll(r) }
            }
            if (any) plans.add(Plan(l, i, Cel(cel.dur).also { it.strokes.addAll(kept) }))
        }
    }
    if (plans.isEmpty()) return 0
    cut.record {
        for (p in plans) {
            val old = p.layer.cels[p.index]
            p.layer.cels[p.index] = p.cel
            TileCache.invalidate(old.strokes)
            ed.referencePoses.remove(old)?.let { ed.referencePoses[p.cel] = it }
        }
    }
    return changed
}

/** Clears the mask pixels (window wx, wy, ww x wh) that lie outside the selection [sel]. */
fun clipMaskToSel(mask: BooleanArray, wx: Int, wy: Int, ww: Int, wh: Int, sel: Sel) {
    for (y in 0 until wh) {
        val ly = wy + y - sel.oy
        for (x in 0 until ww) {
            val i = y * ww + x
            if (!mask[i]) continue
            val lx = wx + x - sel.ox
            if (lx !in 0 until sel.w || ly !in 0 until sel.h || !sel.mask[ly * sel.w + lx]) mask[i] = false
        }
    }
}
