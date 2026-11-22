package com.example.inbetween

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset

// ---------- Fills on vector layers ----------
// A fill is a closed VectorStroke with fill = 1 (baked: just a shape) or 2 (live: also remembers seed, gap, grow and "across layers" so it can be
// recomputed from the lines). Fills are kept at the start of the cel's stroke list, so they are drawn under every line, and the fill follows the
// line centre / its grown flood region, so the line covers the border: no gap between colour and ink.

/** Outline (pixel-edge corners, document pixels) of the first region found in [mask]: the outer boundary of its topmost-leftmost pixel. Null for an empty mask. */
fun traceMaskOutline(mask: BooleanArray, w: Int, h: Int): List<Offset>? {
    var start = -1
    for (i in 0 until w * h) if (mask[i]) { start = i; break }
    if (start < 0) return null
    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
    val x0 = start % w; val y0 = start / w
    var vx = x0; var vy = y0; var dir = 0 // 0 right, 1 down, 2 left, 3 up; the region stays on the right-hand side
    val out = ArrayList<Offset>()
    out.add(Offset(x0.toFloat(), y0.toFloat()))
    val cap = 2L * w * h + 16L
    var steps = 0L
    do {
        val l: Boolean; val r: Boolean
        when (dir) {
            0 -> { l = inside(vx, vy - 1); r = inside(vx, vy) }
            1 -> { l = inside(vx, vy); r = inside(vx - 1, vy) }
            2 -> { l = inside(vx - 1, vy); r = inside(vx - 1, vy - 1) }
            else -> { l = inside(vx - 1, vy - 1); r = inside(vx, vy - 1) }
        }
        val nd = if (!r) (dir + 1) % 4 else if (l) (dir + 3) % 4 else dir
        if (nd != dir && steps > 0) out.add(Offset(vx.toFloat(), vy.toFloat()))
        dir = nd
        when (dir) { 0 -> vx++; 1 -> vy++; 2 -> vx--; else -> vy--; }
        steps++
    } while ((vx != x0 || vy != y0) && steps < cap)
    return out
}

/** The region mask (window origin wx, wy) as smooth fill points: outline traced, the pixel staircase lightly smoothed, then simplified. Null when there is no region. */
fun maskToFillPoints(mask: BooleanArray, w: Int, h: Int, wx: Int, wy: Int): List<VectorPoint>? {
    val c = traceMaskOutline(mask, w, h) ?: return null
    if (c.size < 4) return null
    // No smoothing of the ring any more: the [1 2 1] / 4 passes pulled every sharp tip of the region inward, so the fill stopped short of small corners.
    // The simplification below takes the staircase out of diagonal edges and keeps the tips (they are the farthest points); the drawn curve keeps real corners sharp (see fillPath).
    val ring = ArrayList<VectorPoint>(c.size + 1)
    for (o in c) ring.add(VectorPoint(o.x + wx, o.y + wy))
    ring.add(ring[0])
    val simple = VectorSimplify.rdp(ring, 1.0f, 0f)
    val pts = if (simple.size > 1) simple.subList(0, simple.size - 1) else simple
    return if (pts.size >= 3) pts.toList() else null
}

/**
 * The walls of a fill: a white window (document origin wx, wy; w x h pixels) with the line work drawn black. Only the layer itself, or every visible
 * drawing layer when [across] is on (vector layers as their lines at [tick], raster layers as their painted pixels). Fills never count as walls.
 */
fun vecFillPixels(cut: Cut, layer: Layer, across: Boolean, tick: Int, wx: Int, wy: Int, w: Int, h: Int): IntArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(Color.WHITE)
    cv.translate(-wx.toFloat(), -wy.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val layers = if (across) cut.layers.filter { it.visible && !it.isGroup && it.kind == 0 } else listOf(layer)
    val raster = ArrayList<TileSet>()
    for (l in layers) {
        if (l.vector) {
            val cel = (if (l === layer && !across) l.celAt(tick) else l.drawCel(tick)) ?: continue
            for (s in cel.vectorStrokes()) {
                if (s.fill != 0 || s.points.isEmpty()) continue
                VectorTessellator.draw(cv, s.copy(color = Color.BLACK, opacity = 1f, hardness = 1f, texId = 0, texImg = 0), paint)
            }
        } else l.celAt(tick)?.let { c -> TileCache.peekReady(c.strokes)?.let { raster.add(it) } }
    }
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
    if (raster.isNotEmpty()) {
        val tmp = IntArray(w * h)
        for (set in raster) {
            java.util.Arrays.fill(tmp, 0)
            set.readRect(wx, wy, w, h, tmp)
            for (i in px.indices) if ((tmp[i] ushr 24) >= 64) px[i] = Color.BLACK
        }
    }
    return px
}

/** Flood fill of a fill stroke's recipe against the current lines: the new outline points, or null when nothing sensible comes out. */
fun resolveFillPoints(cut: Cut, layer: Layer, s: VectorStroke, tick: Int, auto: Boolean): List<VectorPoint>? {
    val set = layer.celAt(tick)?.let { TileCache.peek(it.strokes) }
    val win = workWindow(cut, true, set, null, s.fillSeedX, s.fillSeedY)
    val w = win.width(); val h = win.height(); val n = w * h
    if (w < 2 || h < 2) return null
    val px = vecFillPixels(cut, layer, s.fillAcross, tick, win.left, win.top, w, h)
    var sx = (s.fillSeedX.toInt() - win.left).coerceIn(0, w - 1); var sy = (s.fillSeedY.toInt() - win.top).coerceIn(0, h - 1)
    val sn = snapSeed(px, w, h, sx, sy, tolToLevel(WAND_FILL_TOL)); sx = sn[0]; sy = sn[1]
    val lvl = floodMap(px, w, h, sx, sy)
    val ws = FillWork(n); val mask = BooleanArray(n)
    smartFlood(lvl, w, h, sx, sy, tolToLevel(WAND_FILL_TOL), s.fillGap.coerceIn(0, MAX_GAP_FILL), auto, mask, ws)
    if (s.fillGrow != 0) { morph(mask, ws.region, w, h, s.fillGrow); System.arraycopy(ws.region, 0, mask, 0, n) }
    return maskToFillPoints(mask, w, h, win.left, win.top)
}

/**
 * Recomputes every live fill of the keyframes [cels] of [layer] from the lines of that frame (and of the other layers when the fill is "across layers"). One undo step.
 * Returns how many fills changed. The flood work (the slow part) runs one fill at a time with a [yield] in between, so the UI thread keeps handling input and the
 * system never reports "not responding" on a long layer; only the final swap of the new outlines is one short synchronous undo step. [onProgress] gets (done, total).
 * The cut is only read and mutated on the calling (main) thread, as the consistency rules require.
 */
suspend fun refillLiveFills(cut: Cut, layer: Layer, cels: List<Cel>, auto: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
    val jobs = cels.filter { c -> layer.cels.any { it === c } }.distinct()
    val plan = ArrayList<Pair<Cel, List<VectorStroke>>>()
    val total = jobs.sumOf { c -> c.vectorStrokes().count { it.fill == 2 } }
    if (total == 0) return 0
    var done = 0
    for (cel in jobs) {
        val i = layer.cels.indexOfFirst { it === cel }
        if (i < 0) continue // the frame was removed or replaced while we were working
        val strokes = cel.vectorStrokes()
        if (strokes.none { it.fill == 2 }) continue
        val tick = layer.startOf(i)
        var touched = false
        val out = ArrayList<VectorStroke>(strokes.size)
        for (s in strokes) {
            if (s.fill != 2) { out.add(s); continue }
            val pts = resolveFillPoints(cut, layer, s, tick, auto)
            if (pts != null) { touched = true; out.add(s.copy(points = pts)) } else out.add(s)
            done++
            onProgress(done, total)
            kotlinx.coroutines.yield() // let the UI draw and take input between two floods
        }
        if (touched) plan.add(cel to out)
    }
    var changed = 0
    if (plan.isEmpty()) return 0
    cut.record {
        for ((cel, out) in plan) {
            if (layer.cels.none { it === cel }) continue // edited meanwhile: keep the user's newer version
            swapVectorCel(layer, cel, out)
            changed += out.count { it.fill == 2 }
        }
    }
    return changed
}

/** Adds [fill] to the keyframe at [time] (under every line, above older fills), or recolours the fill it was tapped in. */
fun addVectorFill(cut: Cut, layer: Layer, time: Int, fill: VectorStroke, tapX: Float, tapY: Float): Boolean {
    val idx = layer.indexAt(time)
    val cel = layer.cels.getOrNull(idx) ?: return false
    val strokes = cel.vectorStrokes()
    val hit = strokes.indexOfLast { it.fill != 0 && VectorEdit.insideFill(it, tapX, tapY) }
    val out = ArrayList(strokes)
    if (hit >= 0 && VectorEdit.sameRegion(out[hit], fill)) out[hit] = out[hit].copy(color = fill.color) // tapped inside an existing fill of the same region: recolour it
    else out.add(strokes.indexOfLast { it.fill != 0 } + 1, fill)
    replaceVectorCel(cut, layer, idx, out)
    return true
}
